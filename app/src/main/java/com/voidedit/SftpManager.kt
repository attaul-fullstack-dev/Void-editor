package com.voidedit

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.common.SecurityUtils
import java.io.File
import java.io.OutputStream
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

class SftpManager {
    data class Config(
        val host: String,
        val port: Int,
        val username: String,
        val password: String? = null,
        val privateKeyPath: String? = null,
        val passphrase: String? = null,
        val trustedFingerprint: String? = null
    )

    data class Entry(
        val name: String,
        val path: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long,
        val permissions: String
    )

    data class DownloadItem(
        val name: String,
        val path: String,
        val directory: Boolean
    )

    sealed class ConnectResult {
        data class Connected(val home: String) : ConnectResult()
        data class HostKeyRequired(val fingerprint: String) : ConnectResult()
    }

    private val lock = ReentrantLock()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var pendingFingerprint: String? = null

    /**
     * Config terakhir yang BERHASIL terautentikasi. Dipakai untuk memulihkan sesi secara
     * senyap bila server memutus koneksi idle (penyebab utama "Save ke SFTP gagal": user
     * mengedit beberapa menit, sesi mati, lalu tulis ditolak "Koneksi SFTP terputus").
     */
    private var lastConfig: Config? = null

    /** Salinan config terautentikasi untuk koneksi transfer terpisah. */
    fun authenticatedConfig(): Config? = lock.withLock { lastConfig?.copy() }

    fun connect(config: Config): ConnectResult = lock.withLock {
        disconnectLocked()
        require(config.host.isNotBlank()) { "Host wajib diisi" }
        require(config.username.isNotBlank()) { "Username wajib diisi" }
        require(config.port in 1..65535) { "Port tidak valid" }

        val client = SSHClient()
        client.setConnectTimeout(15_000)
        client.setTimeout(30_000)
        var observed: String? = null
        var hostKeyAccepted: Boolean? = null
        client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprint(key)
        observed = fingerprint
        return (config.trustedFingerprint != null && constantTimeEquals(config.trustedFingerprint, fingerprint))
            .also { hostKeyAccepted = it }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        return emptyList()
    }
})
        try {
            client.connect(config.host, config.port)
        } catch (error: Exception) {
            runCatching { client.close() }
            if (observed != null && config.trustedFingerprint == null) {
                pendingFingerprint = observed
                return ConnectResult.HostKeyRequired(observed!!)
            }
            if (hostKeyAccepted == false && config.trustedFingerprint != null) {
                throw SecurityException("Fingerprint host berubah. Koneksi ditolak.", error)
            }
            throw error
        }

        try {
            when {
                !config.privateKeyPath.isNullOrBlank() -> {
                    val keys = if (config.passphrase.isNullOrEmpty()) {
                        client.loadKeys(config.privateKeyPath)
                    } else {
                        client.loadKeys(config.privateKeyPath, object : PasswordFinder {
                            override fun reqPassword(resource: Resource<*>?): CharArray = config.passphrase.toCharArray()
                            override fun shouldRetry(resource: Resource<*>?): Boolean = false
                        })
                    }
                    client.authPublickey(config.username, keys)
                }
                config.password != null -> client.authPassword(config.username, config.password)
                else -> error("Password atau private key wajib dipilih")
            }
            // Keep-alive: tanpa ini server/NAT memutus sesi yang idle beberapa menit dan
            // operasi tulis berikutnya gagal walau UI masih menampilkan "tersambung".
            runCatching { client.connection.keepAlive.keepAliveInterval = 30 }
            val channel = client.newSFTPClient()
            ssh = client
            sftp = channel
            pendingFingerprint = null
            // Simpan config lengkap (termasuk fingerprint terpercaya) untuk pemulihan sesi.
            lastConfig = config
            ConnectResult.Connected(channel.canonicalize("."))
        } catch (error: Exception) {
            runCatching { client.disconnect() }
            runCatching { client.close() }
            throw error
        }
    }

    fun list(path: String, showHidden: Boolean, sortAscending: Boolean): List<Entry> = withClient { client ->
        // Folder selalu di atas; nama diurutkan A–Z atau Z–A sesuai preferensi user.
        val nameOrder: Comparator<String> = if (sortAscending) naturalOrder() else reverseOrder()
        client.ls(normalize(path)).asSequence()
            .filter { it.name != "." && it.name != ".." }
            .filter { showHidden || !it.name.startsWith(".") }
            .map {
                val attrs = it.attributes
                Entry(
                    name = it.name,
                    path = join(path, it.name),
                    directory = attrs.type == FileMode.Type.DIRECTORY,
                    size = attrs.size,
                    modified = attrs.mtime * 1000L,
                    permissions = formatPermissions(attrs.mode.permissionsMask)
                )
            }
            .sortedWith(compareBy<Entry> { !it.directory }.thenBy(nameOrder) { it.name.lowercase() })
            .toList()
    }

    fun read(path: String, maxBytes: Long = 2L * 1024 * 1024): String = withClient { client ->
        val safePath = normalize(path)
        val attrs = client.stat(safePath)
        require(attrs.size <= maxBytes) { "File terlalu besar untuk editor (maksimum 2 MB)" }
        val bytes = client.open(safePath).use { remote -> remote.RemoteFileInputStream().use { it.readBytes() } }
        require(bytes.none { it == 0.toByte() }) { "File biner tidak dapat dibuka di editor teks" }
        bytes.toString(Charsets.UTF_8)
    }

    /** Baca file sebagai byte mentah (dipakai auto-viewer gambar, Fitur D.1). */
    fun readBytes(path: String, maxBytes: Long = 8L * 1024 * 1024): ByteArray = withClient { client ->
        val safePath = normalize(path)
        val attrs = client.stat(safePath)
        require(attrs.size <= maxBytes) { "File terlalu besar untuk ditampilkan (maksimum 8 MB)" }
        client.open(safePath).use { remote -> remote.RemoteFileInputStream().use { it.readBytes() } }
    }

    fun write(path: String, content: String) = withClient { client ->
        client.open(normalize(path), EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { remote ->
            remote.RemoteFileOutputStream().use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
    }

    fun createFile(path: String) = write(path, "")
    fun createDirectory(path: String) = withClient { it.mkdirs(normalize(path)) }
    fun rename(from: String, to: String) = withClient { it.rename(normalize(from), normalize(to)) }

    fun delete(path: String) = withClient { deleteRecursive(it, normalize(path)) }

    fun upload(local: File, remotePath: String) = withClient { client ->
        client.fileTransfer.upload(local.absolutePath, normalize(remotePath))
    }

    fun uploadTree(localRoot: File, remoteRoot: String, onProgress: (Int, Int, String) -> Unit) = withClient { client ->
        val files = localRoot.walkTopDown().toList()
        files.forEachIndexed { index, file ->
            val relative = file.relativeTo(localRoot).invariantSeparatorsPath
            val target = if (relative.isEmpty()) normalize(remoteRoot) else join(remoteRoot, relative)
            if (file.isDirectory) runCatching { client.mkdirs(target) }
            else client.fileTransfer.upload(file.absolutePath, target)
            onProgress(index + 1, files.size, relative.ifEmpty { localRoot.name })
        }
    }

    /** Stream satu file remote langsung ke output SAF tanpa memuat seluruh file ke memori. */
    fun downloadFile(
        path: String,
        output: OutputStream,
        checkCancelled: () -> Unit = {}
    ) = withClient(retryOnDisconnect = false) { client ->
        copyRemoteFile(client, normalize(path), output, checkCancelled)
    }

    /**
     * Stream pilihan remote sebagai ZIP sambil traversal. Tidak ada daftar seluruh tree di
     * memori, sehingga folder besar langsung menghasilkan output. Hidden file tetap disertakan
     * dan symlink tidak diikuti sebagai folder.
     */
    fun downloadZip(
        items: List<DownloadItem>,
        output: OutputStream,
        checkCancelled: () -> Unit = {},
        onProgress: (Int, Int, String) -> Unit
    ) = withClient(retryOnDisconnect = false) { client ->
        require(items.isNotEmpty()) { "Tidak ada item yang dipilih" }
        var completed = 0
        val usedPaths = mutableSetOf<String>()
        ZipOutputStream(output.buffered()).use { zip ->
            items.forEach { item ->
                checkCancelled()
                writeZipSource(
                    client = client,
                    remotePath = normalize(item.path),
                    entryName = safeZipSegment(item.name),
                    zip = zip,
                    depth = 0,
                    usedPaths = usedPaths,
                    checkCancelled = checkCancelled,
                    onEntry = { label, finished ->
                        if (finished) completed += 1
                        onProgress(completed, 0, label)
                    }
                )
            }
        }
        onProgress(completed, completed, "Selesai")
    }

    /** Putus eksplisit oleh user: kredensial pemulihan dibuang agar tidak reconnect senyap. */
    fun disconnect() = lock.withLock {
        lastConfig = null
        disconnectLocked()
    }

    /** True bila sesi hidup ATAU masih bisa dipulihkan otomatis (dipakai UI/tombol Back). */
    fun isConnected(): Boolean = lock.withLock { sessionAlive() || lastConfig != null }

    private fun deleteRecursive(client: SFTPClient, path: String) {
        // lstat mencegah symlink ke direktori diikuti dan menghapus isi targetnya.
        val attrs = client.lstat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            client.ls(path)
                .filter { it.name != "." && it.name != ".." }
                .forEach { deleteRecursive(client, join(path, it.name)) }
            client.rmdir(path)
        } else client.rm(path)
    }

    private fun writeZipSource(
        client: SFTPClient,
        remotePath: String,
        entryName: String,
        zip: ZipOutputStream,
        depth: Int,
        usedPaths: MutableSet<String>,
        directoryHint: Boolean? = null,
        checkCancelled: () -> Unit = {},
        onEntry: (String, Boolean) -> Unit
    ) {
        checkCancelled()
        require(depth <= MAX_DOWNLOAD_DEPTH) { "Folder terlalu dalam untuk dijadikan ZIP" }
        val directory = directoryHint ?: (client.lstat(remotePath).type == FileMode.Type.DIRECTORY)
        val uniqueEntryName = uniqueZipPath(entryName, usedPaths)
        val zipName = if (directory) uniqueEntryName + "/" else uniqueEntryName
        onEntry(zipName, false)
        zip.putNextEntry(ZipEntry(zipName))
        if (!directory) copyRemoteFile(client, remotePath, zip, checkCancelled)
        zip.closeEntry()
        onEntry(zipName, true)
        if (!directory) return
        client.ls(remotePath)
            .filter { it.name != "." && it.name != ".." }
            .forEach { child ->
                checkCancelled()
                writeZipSource(
                    client,
                    join(remotePath, child.name),
                    uniqueEntryName + "/" + safeZipSegment(child.name),
                    zip,
                    depth + 1,
                    usedPaths,
                    child.attributes.type == FileMode.Type.DIRECTORY,
                    checkCancelled,
                    onEntry
                )
            }
    }

    private fun copyRemoteFile(
        client: SFTPClient,
        path: String,
        output: OutputStream,
        checkCancelled: () -> Unit
    ) {
        client.open(path).use { remote ->
            remote.RemoteFileInputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun sessionAlive(): Boolean = sftp != null && ssh?.isConnected == true && ssh?.isAuthenticated == true

    /**
     * Semua operasi lewat sini. Bila sesi sudah mati tapi kita masih punya kredensial yang
     * terbukti benar, sesi dipulihkan sekali secara senyap sebelum operasi dijalankan —
     * user tidak perlu reconnect manual, dan Save tidak lagi gagal karena idle timeout.
     */
    private fun <T> withClient(
        retryOnDisconnect: Boolean = true,
        block: (SFTPClient) -> T
    ): T = lock.withLock {
        if (!sessionAlive()) {
            val config = lastConfig ?: error("Belum terhubung ke server")
            val recovered = runCatching { connect(config) }.getOrNull()
            if (recovered !is ConnectResult.Connected) error("Koneksi SFTP terputus dan gagal dipulihkan")
        }
        val client = sftp ?: error("Belum terhubung ke server")
        try {
            block(client)
        } catch (error: Exception) {
            // Koneksi bisa mati tepat di tengah operasi: coba sekali lagi dengan sesi baru.
            if (!retryOnDisconnect || sessionAlive()) throw error
            val config = lastConfig ?: throw error
            val recovered = runCatching { connect(config) }.getOrNull()
            if (recovered !is ConnectResult.Connected) throw error
            block(sftp ?: throw error)
        }
    }

    private fun disconnectLocked() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        sftp = null
        ssh = null
    }

    companion object {
        private const val MAX_DOWNLOAD_DEPTH = 256

        /**
         * Ubah 9 bit izin POSIX menjadi "rwxr-xr-x". Sebelumnya nama enum yang dipakai,
         * sehingga kolom izin di UI tampil seperti "USR_RUSR_W…".
         */
        fun formatPermissions(mask: Int): String {
            val symbols = charArrayOf('r', 'w', 'x')
            return (0 until 9).joinToString("") { index ->
                if (mask and (1 shl (8 - index)) != 0) symbols[index % 3].toString() else "-"
            }
        }

        fun normalize(raw: String): String {
            val absolute = raw.startsWith('/')
            val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            val clean = mutableListOf<String>()
            parts.forEach { part -> if (part == "..") { if (clean.isNotEmpty()) clean.removeAt(clean.lastIndex) } else clean += part }
            val result = clean.joinToString("/")
            // "/$result" tidak mungkin kosong, jadi ifEmpty di sini dulunya kode mati.
            return if (absolute) "/$result" else result.ifEmpty { "." }
        }

        fun join(parent: String, child: String): String = normalize("${parent.trimEnd('/')}/$child")

        fun validateName(name: String) {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) { "Nama tidak valid" }
        }

        /** Perbarui target editor ketika file atau salah satu folder induknya di-rename. */
        fun remapPathAfterRename(activePath: String?, from: String, to: String): String? {
            if (activePath == null) return null
            val active = normalize(activePath)
            val source = normalize(from)
            val target = normalize(to)
            return when {
                active == source -> target
                active.startsWith("$source/") -> target + active.removePrefix(source)
                else -> activePath
            }
        }

        /** True bila path aktif ikut terhapus, termasuk saat folder induknya dihapus. */
        fun containsPath(parent: String, candidate: String?): Boolean {
            if (candidate == null) return false
            val normalizedParent = normalize(parent)
            val normalizedCandidate = normalize(candidate)
            return normalizedCandidate == normalizedParent || normalizedCandidate.startsWith("$normalizedParent/")
        }

        internal fun uniqueZipPath(proposed: String, usedPaths: MutableSet<String>): String {
            val clean = proposed.trimEnd('/')
            if (usedPaths.add(clean)) return clean
            val parent = clean.substringBeforeLast('/', "")
            val leaf = clean.substringAfterLast('/')
            val dot = leaf.lastIndexOf('.').takeIf { it > 0 } ?: leaf.length
            val stem = leaf.substring(0, dot)
            val extension = leaf.substring(dot)
            var index = 2
            while (true) {
                val renamedLeaf = "$stem ($index)$extension"
                val candidate = if (parent.isEmpty()) renamedLeaf else "$parent/$renamedLeaf"
                if (usedPaths.add(candidate)) return candidate
                index += 1
            }
        }

        private fun safeZipSegment(name: String): String {
            val cleaned = name.map {
                if (it.code < 32 || it.code == 47 || it.code == 92) "_" else it.toString()
            }.joinToString("").take(255).ifBlank { "item" }
            return if (cleaned == "." || cleaned == "..") "_" + cleaned else cleaned
        }

        private fun fingerprint(key: PublicKey): String {
            val digest = SecurityUtils.getMessageDigest("SHA-256").digest(key.encoded)
            return "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
