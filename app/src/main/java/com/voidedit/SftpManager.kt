package com.voidedit

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.common.SecurityUtils
import java.io.File
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.locks.ReentrantLock
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

    sealed class ConnectResult {
        data class Connected(val home: String) : ConnectResult()
        data class HostKeyRequired(val fingerprint: String) : ConnectResult()
    }

    private val lock = ReentrantLock()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var pendingFingerprint: String? = null

    fun connect(config: Config): ConnectResult = lock.withLock {
        disconnectLocked()
        require(config.host.isNotBlank()) { "Host wajib diisi" }
        require(config.username.isNotBlank()) { "Username wajib diisi" }
        require(config.port in 1..65535) { "Port tidak valid" }

        val client = SSHClient()
        client.setConnectTimeout(15_000)
        client.setTimeout(30_000)
        var observed: String? = null
        client.addHostKeyVerifier(object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = fingerprint(key)
        observed = fingerprint
        return config.trustedFingerprint != null && constantTimeEquals(config.trustedFingerprint, fingerprint)
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
            if (observed != null && config.trustedFingerprint != null) {
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
            val channel = client.newSFTPClient()
            ssh = client
            sftp = channel
            pendingFingerprint = null
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

    fun disconnect() = lock.withLock { disconnectLocked() }
    fun isConnected(): Boolean = lock.withLock { ssh?.isConnected == true && ssh?.isAuthenticated == true }

    private fun deleteRecursive(client: SFTPClient, path: String) {
        val attrs = client.stat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            client.ls(path).filter { it.name != "." && it.name != ".." }.forEach { deleteRecursive(client, join(path, it.name)) }
            client.rmdir(path)
        } else client.rm(path)
    }

    private fun <T> withClient(block: (SFTPClient) -> T): T = lock.withLock {
        val client = sftp ?: error("Belum terhubung ke server")
        check(ssh?.isConnected == true && ssh?.isAuthenticated == true) { "Koneksi SFTP terputus" }
        block(client)
    }

    private fun disconnectLocked() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { ssh?.close() }
        sftp = null
        ssh = null
    }

    companion object {
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

        private fun fingerprint(key: PublicKey): String {
            val digest = SecurityUtils.getMessageDigest("SHA-256").digest(key.encoded)
            return "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }

        private fun constantTimeEquals(a: String, b: String): Boolean = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
