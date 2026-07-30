package com.voidedit

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentFileUri: Uri? = null
    private var isWebViewReady = false
    private var pendingLoadUri: Uri? = null
    private var pendingWriteContent: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sftp = SftpManager()
    private val prefs by lazy { getSharedPreferences("void_sftp", Context.MODE_PRIVATE) }
    private val connectionStore by lazy { SftpConnectionStore(this) }
    private val bookmarkStore by lazy { LocalFolderBookmarkStore(this) }

    // Remote path yang sedang dibuka. Jika tidak null, tombol Save menulis ke server.
    private var activeRemotePath: String? = null

    // True hanya selama penulisan berjalan (bukan saat dialog "Simpan sebagai" terbuka),
    // supaya dua permintaan Save beruntun tidak menulis file yang sama bersamaan.
    private var isWriting = false

    // Launcher pemilihan file yang menyalurkan hasil ke handler dinamis.
    private var pickCallback: ((Uri) -> Unit)? = null

    // requestId yang menunggu hasil ACTION_OPEN_DOCUMENT_TREE (Fitur E).
    private var pendingTreeRequestId: String? = null

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                loadFileFromUri(uri)
            }
        }
    }

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val content = pendingWriteContent
        pendingWriteContent = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri == null || content == null) {
            // Dibatalkan user: tetap laporkan supaya editor tidak menandai file sudah bersih.
            emitResult(SAVE_REQUEST_ID, "save", false, null, "Penyimpanan dibatalkan")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        currentFileUri = uri
        writeToUri(uri, content)
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pickCallback
        pickCallback = null
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> cb?.invoke(uri) }
        }
    }

    // Fitur E: pemilih FOLDER (bukan file). Izin dipertahankan agar tetap bisa dibaca
    // setelah aplikasi di-restart.
    private val treePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val requestId = pendingTreeRequestId
        pendingTreeRequestId = null
        if (requestId == null) return@registerForActivityResult
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri == null) {
            emitResult(requestId, "pickTree", false, null, "Pemilihan folder dibatalkan")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        val data = JSONObject()
            .put("treeUri", uri.toString())
            .put("name", treeDisplayName(uri))
        emitResult(requestId, "pickTree", true, data, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    Security.removeProvider("BC")
    Security.insertProviderAt(BouncyCastleProvider(), 1)

    supportActionBar?.hide()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // WebView membatasi teks minimum 8px secara default (minimumFontSize /
            // minimumLogicalFontSize), sehingga opsi ukuran font 6px & 4px akan diabaikan.
            // Turunkan batasnya agar seluruh opsi di dropdown benar-benar berlaku.
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            textZoom = 100
            // TEXT_AUTOSIZING (default di banyak WebView) MEMBESARKAN teks kecil secara
            // otomatis, sehingga 4px/6px tetap terlihat sama besar walau minimumFontSize
            // sudah 1. NORMAL mematikan penyesuaian itu agar ukuran font persis seperti CSS.
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            // Font monospace editor tidak boleh ikut skala "ukuran font" sistem.
            defaultFontSize = 16
            defaultFixedFontSize = 13
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String) {
                isWebViewReady = true
                pendingLoadUri?.let {
                    loadFileFromUri(it)
                    pendingLoadUri = null
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        webView.loadUrl("file:///android_asset/voidedit.html")
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        // Sesi hanya diputus saat Activity benar-benar mati. Selama app masih berjalan
        // (pindah layar Explorer, buka file, background singkat) koneksi dibiarkan hidup —
        // SftpManager juga memulihkan sesi otomatis bila server memutus koneksi idle.
        scope.cancel()
        runCatching { sftp.disconnect() }
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                if (isWebViewReady) loadFileFromUri(uri)
                else pendingLoadUri = uri
            }
        }
    }

    private fun loadFileFromUri(uri: Uri) {
        try {
            val fileName = getFileName(uri)
            // Gambar tidak punya mode edit teks: langsung tampilkan viewer (Fitur D.1).
            if (isImage(fileName, contentResolver.getType(uri))) {
                val bytes = readUriBytes(uri)
                currentFileUri = null
                activeRemotePath = null
                dispatchImage(bytes, mimeFor(fileName, contentResolver.getType(uri)), fileName)
                return
            }
            val content = readUriText(uri)
            currentFileUri = uri
            activeRemotePath = null
            dispatchLoad(content, fileName, null)
        } catch (e: Exception) {
            toast("Gagal buka file: ${e.message}")
        }
    }

    private fun readUriText(uri: Uri, maxBytes: Long = 2L * 1024 * 1024): String {
        val bytes = readUriBytes(uri, maxBytes)
        require(bytes.none { it == 0.toByte() }) { "File biner tidak dapat dibuka di editor teks" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readUriBytes(uri: Uri, maxBytes: Long = 4L * 1024 * 1024): ByteArray {
        val stream = contentResolver.openInputStream(uri) ?: error("Tidak dapat membaca file")
        stream.use { input ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            var read = input.read(buffer)
            while (read >= 0) {
                if (output.size() + read > maxBytes) error("File terlalu besar (maksimum ${maxBytes / (1024 * 1024)} MB)")
                output.write(buffer, 0, read)
                read = input.read(buffer)
            }
            return output.toByteArray()
        }
    }

    // Kirim gambar ke viewer khusus di WebView (bukan textarea).
    private fun dispatchImage(bytes: ByteArray, mime: String, name: String) {
        val payload = JSONObject()
            .put("name", name)
            .put("mime", mime)
            .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .toString()
        webView.post {
            webView.evaluateJavascript("window.__voidLoadImage && window.__voidLoadImage($payload)", null)
        }
    }

    private fun treeDisplayName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.lastPathSegment?.substringAfterLast('/') ?: "Folder"
        return id.substringAfterLast(':').trimEnd('/').substringAfterLast('/').ifBlank { "Folder" }
    }

    private fun documentIdOf(uri: Uri): String =
        if (DocumentsContract.isDocumentUri(this, uri)) DocumentsContract.getDocumentId(uri)
        else DocumentsContract.getTreeDocumentId(uri)

    // Listing folder SAF lewat DocumentsContract (content URI, bukan java.io.File).
    // Filter "berkas tersembunyi" membaca SharedPreferences yang SAMA dengan listing SFTP
    // (satu sumber kebenaran), sehingga toggle di Pengaturan berlaku konsisten di semua listing.
    private fun listTree(uriString: String, showHiddenOverride: Boolean? = null): JSONArray {
        // Satu sumber kebenaran: nilai dari WebView bila dikirim, kalau tidak baca
        // SharedPreferences yang sama dengan listing SFTP.
        val showHidden = showHiddenOverride ?: prefs.getBoolean("showHidden", false)
        val uri = Uri.parse(uriString)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentIdOf(uri))
        val rows = mutableListOf<JSONObject>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: docId.substringAfterLast('/')
                if (!showHidden && name.startsWith(".")) continue
                val mime = cursor.getString(2) ?: ""
                val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                rows += JSONObject()
                    .put("name", name)
                    .put("uri", DocumentsContract.buildDocumentUriUsingTree(uri, docId).toString())
                    .put("directory", directory)
                    .put("size", if (cursor.isNull(3)) 0L else cursor.getLong(3))
                    .put("modified", if (cursor.isNull(4)) 0L else cursor.getLong(4))
                    .put("mime", mime)
            }
        } ?: error("Folder tidak dapat dibaca. Tambahkan ulang jalur ini.")
        val ascending = prefs.getBoolean("sortAscending", true)
        val nameOrder: Comparator<String> = if (ascending) naturalOrder() else reverseOrder()
        val sorted = rows.sortedWith(
            compareBy<JSONObject> { !it.optBoolean("directory") }
                .thenBy(nameOrder) { it.optString("name").lowercase() }
        )
        val array = JSONArray()
        sorted.forEach { array.put(it) }
        return array
    }

    // Kirim konten file ke editor via JSON agar aman terhadap backtick/backslash/Unicode.
    private fun dispatchLoad(content: String, name: String, remotePath: String?) {
        val payload = JSONObject()
            .put("content", content)
            .put("name", name)
            .put("remotePath", remotePath ?: JSONObject.NULL)
            .toString()
        webView.post {
            webView.evaluateJavascript("window.__voidLoadFile && window.__voidLoadFile($payload)", null)
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "untitled.txt"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
            }
        } catch (_: Exception) {}
        return name
    }

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun isImage(name: String, mime: String?): Boolean =
        mime?.startsWith("image/") == true || extensionOf(name) in IMAGE_EXTENSIONS

    private fun mimeFor(name: String, mime: String?): String {
        if (mime?.startsWith("image/") == true) return mime
        return when (extensionOf(name)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    /**
     * Tulis ke content URI. Melempar bila gagal — pemanggil wajib melaporkan hasilnya.
     * Mode "wt" (truncate) tidak didukung semua DocumentsProvider, jadi ada fallback "w"
     * yang memotong sisa file lama secara manual agar file tidak berisi ekor konten lama.
     */
    private fun writeUriOrThrow(uri: Uri, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val written = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Stream tulis tidak tersedia")
        }
        if (written.isSuccess) return
        contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
            java.io.FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.truncate(0)
                stream.write(bytes)
                stream.flush()
            }
        } ?: throw (written.exceptionOrNull() ?: IllegalStateException("File tidak dapat ditulis"))
    }

    /**
     * "Simpan sebagai". Bila pemilih berkas sistem tidak dapat dibuka (mis. tidak ada
     * DocumentsUI di ROM), kegagalan WAJIB dilaporkan — kalau tidak, Promise Save di
     * WebView menggantung selamanya dan alur "keluar setelah simpan" ikut macet.
     */
    private fun launchSaveAs(content: String, fileName: String) {
        pendingWriteContent = content
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        val launched = runCatching { saveAsLauncher.launch(intent) }
        if (launched.isFailure) {
            pendingWriteContent = null
            val message = launched.exceptionOrNull()?.message ?: "Pemilih berkas tidak tersedia"
            toast("Gagal simpan: $message")
            emitResult(SAVE_REQUEST_ID, "save", false, null, message)
        }
    }

    private fun writeToUri(uri: Uri, content: String) {
        val result = runCatching { writeUriOrThrow(uri, content) }
        result.fold(
            onSuccess = {
                toast("✓ Tersimpan")
                emitResult(SAVE_REQUEST_ID, "save", true, JSONObject().put("target", "local"), null)
            },
            onFailure = {
                val message = it.message ?: "File tidak dapat ditulis"
                toast("Gagal simpan: $message")
                emitResult(SAVE_REQUEST_ID, "save", false, null, message)
            }
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // Salin content:// ke cache lalu jalankan aksi; hapus temp di finally.
    private fun withCachedFile(uri: Uri, prefix: String, block: (File, String) -> Unit) {
        val name = getFileName(uri)
        val temp = File.createTempFile(prefix, "_$name", cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Tidak dapat membaca file terpilih")
            block(temp, name)
        } finally {
            temp.delete()
        }
    }

    // Unzip aman dengan proteksi Zip Slip + batas ukuran/jumlah entry.
    private fun extractZip(uri: Uri, destination: File): Int {
        val maxEntries = 5_000
        val maxBytes = 200L * 1024 * 1024
        var totalBytes = 0L
        var count = 0
        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val canonicalRoot = destination.canonicalPath + File.separator
                var entry = zip.nextEntry
                while (entry != null) {
                    if (++count > maxEntries) error("Arsip ZIP memiliki terlalu banyak entry")
                    val target = File(destination, entry.name)
                    if (!target.canonicalPath.startsWith(canonicalRoot) && target.canonicalPath != destination.canonicalPath) {
                        error("Entry ZIP tidak aman terdeteksi (Zip Slip)")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buffer = ByteArray(8192)
                            var read = zip.read(buffer)
                            while (read >= 0) {
                                totalBytes += read
                                if (totalBytes > maxBytes) error("Ukuran ZIP melebihi batas 200 MB")
                                out.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Tidak dapat membaca arsip ZIP")
        return count
    }

    private fun emitResult(requestId: String, action: String, success: Boolean, data: Any?, error: String?) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("action", action)
            .put("success", success)
            .put("data", data ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .toString()
        webView.post {
            webView.evaluateJavascript("window.onSftpResult && window.onSftpResult($payload)", null)
        }
    }

    private fun emitProgress(requestId: String, done: Int, total: Int, label: String) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("done", done)
            .put("total", total)
            .put("label", label)
            .toString()
        webView.post {
            webView.evaluateJavascript("window.onSftpProgress && window.onSftpProgress($payload)", null)
        }
    }

    private fun entriesToJson(entries: List<SftpManager.Entry>): JSONArray {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("path", it.path)
                    .put("directory", it.directory)
                    .put("size", it.size)
                    .put("modified", it.modified)
                    .put("permissions", it.permissions)
            )
        }
        return array
    }

    // Bungkus operasi network di IO + serialize hasil ke UI.
    private fun runSftp(requestId: String, action: String, work: () -> Any?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            result.fold(
                onSuccess = { emitResult(requestId, action, true, it, null) },
                onFailure = { emitResult(requestId, action, false, null, it.message ?: "Terjadi kesalahan") }
            )
        }
    }

    inner class AndroidBridge {
        /**
         * Satu-satunya jalur Save. Hasilnya SELALU dilaporkan ke WebView lewat requestId
         * tetap SAVE_REQUEST_ID + Toast native, baik untuk SFTP, file lokal, maupun
         * "Simpan sebagai" — tidak ada lagi kegagalan yang hilang tanpa jejak.
         */
        @JavascriptInterface
        fun onSaveRequest(content: String, fileName: String) {
            runOnUiThread {
                if (isWriting) {
                    emitResult(SAVE_REQUEST_ID, "save", false, null, "Penyimpanan sebelumnya masih berjalan")
                    return@runOnUiThread
                }
                val remote = activeRemotePath
                if (remote != null) {
                    isWriting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { sftp.write(remote, content) } }
                        isWriting = false
                        result.fold(
                            onSuccess = {
                                toast("✓ Tersimpan ke server")
                                emitResult(SAVE_REQUEST_ID, "save", true, JSONObject().put("target", "remote").put("path", remote), null)
                            },
                            onFailure = {
                                val message = it.message ?: "Gagal menulis ke server"
                                toast("Gagal simpan ke server: $message")
                                emitResult(SAVE_REQUEST_ID, "save", false, null, message)
                            }
                        )
                    }
                    return@runOnUiThread
                }

                val uri = currentFileUri
                if (uri != null) {
                    isWriting = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { writeUriOrThrow(uri, content) } }
                        isWriting = false
                        result.fold(
                            onSuccess = {
                                toast("✓ Tersimpan")
                                emitResult(SAVE_REQUEST_ID, "save", true, JSONObject().put("target", "local").put("name", fileName), null)
                            },
                            onFailure = {
                                // Izin SAF bisa hilang setelah restart / file dipindah: jangan
                                // gagal diam-diam, tawarkan "Simpan sebagai" sebagai jalan keluar.
                                toast("Gagal simpan: ${it.message}. Pilih lokasi baru.")
                                currentFileUri = null
                                launchSaveAs(content, fileName)
                            }
                        )
                    }
                    return@runOnUiThread
                }

                launchSaveAs(content, fileName)
            }
        }

        @JavascriptInterface
        fun sftpConnect(requestId: String, configJson: String) {
            val json = JSONObject(configJson)
            runSftp(requestId, "connect") {
                val host = json.getString("host").trim()
                val port = json.optInt("port", 22)
                val config = configFromJson(json, trustedFingerprint(host, port))
                when (val outcome = sftp.connect(config)) {
                    is SftpManager.ConnectResult.Connected ->
                        JSONObject().put("status", "connected").put("home", outcome.home)
                    is SftpManager.ConnectResult.HostKeyRequired ->
                        JSONObject().put("status", "hostKey").put("fingerprint", outcome.fingerprint)
                }
            }
        }

        // Simpan fingerprint yang disetujui user lalu sambung ulang.
        @JavascriptInterface
        fun sftpTrustHostKey(requestId: String, configJson: String) {
            val json = JSONObject(configJson)
            val host = json.getString("host").trim()
            val port = json.optInt("port", 22)
            val fingerprint = json.getString("fingerprint")
            runSftp(requestId, "connect") {
                prefs.edit().putString(hostKeyPref(host, port), fingerprint).apply()
                val config = SftpManager.Config(
                    host = host,
                    port = port,
                    username = json.getString("username").trim(),
                    password = json.optString("password").ifEmpty { null },
                    privateKeyPath = json.optString("privateKeyPath").ifEmpty { null },
                    passphrase = json.optString("passphrase").ifEmpty { null },
                    trustedFingerprint = fingerprint
                )
                when (val outcome = sftp.connect(config)) {
                    is SftpManager.ConnectResult.Connected ->
                        JSONObject().put("status", "connected").put("home", outcome.home)
                    is SftpManager.ConnectResult.HostKeyRequired ->
                        JSONObject().put("status", "hostKey").put("fingerprint", outcome.fingerprint)
                }
            }
        }

        @JavascriptInterface
        fun sftpList(requestId: String, path: String, showHidden: Boolean, sortAscending: Boolean) {
            runSftp(requestId, "list") { entriesToJson(sftp.list(path, showHidden, sortAscending)) }
        }

        @JavascriptInterface
        fun sftpOpenFile(requestId: String, path: String) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { sftp.read(path) } }
                result.fold(
                    onSuccess = { content ->
                        activeRemotePath = path
                        currentFileUri = null
                        dispatchLoad(content, path.substringAfterLast('/'), path)
                        emitResult(requestId, "open", true, JSONObject().put("path", path), null)
                    },
                    onFailure = { emitResult(requestId, "open", false, null, it.message ?: "Gagal membuka file") }
                )
            }
        }

        @JavascriptInterface
        fun sftpCreateFile(requestId: String, parent: String, name: String) {
            runSftp(requestId, "create") {
                SftpManager.validateName(name)
                val path = SftpManager.join(parent, name)
                sftp.createFile(path); JSONObject().put("path", path)
            }
        }

        @JavascriptInterface
        fun sftpCreateFolder(requestId: String, parent: String, name: String) {
            runSftp(requestId, "create") {
                SftpManager.validateName(name)
                val path = SftpManager.join(parent, name)
                sftp.createDirectory(path); JSONObject().put("path", path)
            }
        }

        @JavascriptInterface
        fun sftpRename(requestId: String, parent: String, oldName: String, newName: String) {
            runSftp(requestId, "rename") {
                SftpManager.validateName(newName)
                val from = SftpManager.join(parent, oldName)
                val to = SftpManager.join(parent, newName)
                sftp.rename(from, to); JSONObject().put("path", to)
            }
        }

        @JavascriptInterface
        fun sftpDelete(requestId: String, path: String) {
            runSftp(requestId, "delete") {
                sftp.delete(path)
                if (activeRemotePath == path) runOnUiThread { activeRemotePath = null }
                JSONObject().put("path", path)
            }
        }

        @JavascriptInterface
        fun sftpDisconnect(requestId: String) {
            runSftp(requestId, "disconnect") { sftp.disconnect(); JSONObject().put("status", "disconnected") }
        }

        // Pilih file lalu upload ke direktori remote aktif.
        @JavascriptInterface
        fun sftpUpload(requestId: String, remoteDir: String) {
            runOnUiThread {
                pickCallback = { uri ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                var target = ""
                                withCachedFile(uri, "upload") { file, name ->
                                    target = SftpManager.join(remoteDir, name)
                                    sftp.upload(file, target)
                                }
                                target
                            }
                        }
                        result.fold(
                            onSuccess = { emitResult(requestId, "upload", true, JSONObject().put("path", it), null) },
                            onFailure = { emitResult(requestId, "upload", false, null, it.message ?: "Upload gagal") }
                        )
                    }
                }
                pickFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            }
        }

        // Pilih ZIP, ekstrak aman ke cache, unggah tree ke remote, lalu bersihkan.
        @JavascriptInterface
        fun sftpImportZip(requestId: String, remoteDir: String) {
            runOnUiThread {
                pickCallback = { uri ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val workDir = File(cacheDir, "zip_${System.currentTimeMillis()}")
                                workDir.mkdirs()
                                try {
                                    extractZip(uri, workDir)
                                    sftp.uploadTree(workDir, remoteDir) { done, total, label ->
                                        emitProgress(requestId, done, total, label)
                                    }
                                } finally {
                                    workDir.deleteRecursively()
                                }
                                JSONObject().put("path", remoteDir)
                            }
                        }
                        result.fold(
                            onSuccess = { emitResult(requestId, "importZip", true, it, null) },
                            onFailure = { emitResult(requestId, "importZip", false, null, it.message ?: "Import ZIP gagal") }
                        )
                    }
                }
                pickFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                })
            }
        }

        @JavascriptInterface
        fun sftpPickPrivateKey(requestId: String) {
            runOnUiThread {
                pickCallback = { uri ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val name = getFileName(uri)
                                val dest = File(filesDir, "keys").apply { mkdirs() }.let { File(it, "id_${System.currentTimeMillis()}") }
                                contentResolver.openInputStream(uri)?.use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output) }
                                } ?: error("Tidak dapat membaca key")
                                JSONObject().put("path", dest.absolutePath).put("name", name)
                            }
                        }
                        result.fold(
                            onSuccess = { emitResult(requestId, "pickKey", true, it, null) },
                            onFailure = { emitResult(requestId, "pickKey", false, null, it.message ?: "Gagal memilih key") }
                        )
                    }
                }
                pickFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            }
        }

        /* ─────────── FITUR A — koneksi SFTP tersimpan ─────────── */

        @JavascriptInterface
        fun savedConnections(): String {
            val array = JSONArray()
            connectionStore.list().forEach { array.put(it.toPublicJson()) }
            return JSONObject()
                .put("available", connectionStore.available)
                .put("items", array)
                .toString()
        }

        /** Detail termasuk kredensial — hanya dipanggil saat user membuka dialog Edit. */
        @JavascriptInterface
        fun savedConnectionDetail(id: String): String {
            val saved = connectionStore.get(id) ?: return JSONObject().put("found", false).toString()
            return saved.toDetailJson().put("found", true).toString()
        }

        @JavascriptInterface
        fun saveConnection(configJson: String, label: String): String = wrapSync {
            val json = JSONObject(configJson)
            val id = connectionStore.save(label, configFromJson(json), authTypeOf(json))
            JSONObject().put("id", id)
        }

        @JavascriptInterface
        fun updateConnection(id: String, configJson: String, label: String): String = wrapSync {
            val json = JSONObject(configJson)
            connectionStore.update(id, label, configFromJson(json), authTypeOf(json))
            JSONObject().put("id", id)
        }

        @JavascriptInterface
        fun renameConnection(id: String, label: String): String = wrapSync {
            connectionStore.rename(id, label); JSONObject().put("id", id)
        }

        @JavascriptInterface
        fun deleteConnection(id: String): String = wrapSync {
            connectionStore.delete(id); JSONObject().put("id", id)
        }

        /** Connect memakai kredensial tersimpan — password tidak pernah dikirim ke WebView. */
        @JavascriptInterface
        fun sftpConnectSaved(requestId: String, id: String) {
            runSftp(requestId, "connect") {
                val saved = connectionStore.get(id) ?: error("Koneksi tersimpan tidak ditemukan")
                connectOutcome(
                    savedToConfig(saved, trustedFingerprint(saved.host, saved.port)),
                    "${saved.username}@${saved.host}"
                ).put("id", id)
            }
        }

        @JavascriptInterface
        fun sftpTrustSavedHostKey(requestId: String, id: String, fingerprint: String) {
            runSftp(requestId, "connect") {
                val saved = connectionStore.get(id) ?: error("Koneksi tersimpan tidak ditemukan")
                prefs.edit().putString(hostKeyPref(saved.host, saved.port), fingerprint).apply()
                connectOutcome(savedToConfig(saved, fingerprint), "${saved.username}@${saved.host}")
                    .put("id", id)
            }
        }

        /* ─────────── FITUR C — Select document (satu file lokal) ─────────── */

        @JavascriptInterface
        fun pickLocalDocument() {
            runOnUiThread {
                openFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            }
        }

        /* ─────────── FITUR D.1 — auto-viewer gambar remote ──────────�� */

        @JavascriptInterface
        fun sftpOpenImage(requestId: String, path: String) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { sftp.readBytes(path, 4L * 1024 * 1024) } }
                result.fold(
                    onSuccess = { bytes ->
                        val name = path.substringAfterLast('/')
                        activeRemotePath = null   // gambar bersifat read-only
                        currentFileUri = null
                        dispatchImage(bytes, mimeFor(name, null), name)
                        emitResult(requestId, "openImage", true, JSONObject().put("path", path), null)
                    },
                    onFailure = { emitResult(requestId, "openImage", false, null, it.message ?: "Gagal membuka gambar") }
                )
            }
        }

        /* ─────────── FITUR E — bookmark folder lokal (SAF tree) ─────────── */

        @JavascriptInterface
        fun pickFolderTree(requestId: String) {
            runOnUiThread {
                pendingTreeRequestId = requestId
                treePickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            }
        }

        @JavascriptInterface
        fun localBookmarks(): String {
            val array = JSONArray()
            bookmarkStore.list().forEach { array.put(it.toJson()) }
            return array.toString()
        }

        @JavascriptInterface
        fun addLocalBookmark(treeUri: String, label: String): String = wrapSync {
            JSONObject().put("id", bookmarkStore.add(label, treeUri))
        }

        @JavascriptInterface
        fun renameLocalBookmark(id: String, label: String): String = wrapSync {
            bookmarkStore.rename(id, label); JSONObject().put("id", id)
        }

        @JavascriptInterface
        fun deleteLocalBookmark(id: String): String = wrapSync {
            bookmarkStore.delete(id); JSONObject().put("id", id)
        }

        /**
         * Listing folder bookmark lokal. showHidden dikirim eksplisit oleh WebView (nilai
         * yang sama yang dipakai sftpList) sehingga toggle "tampilkan berkas tersembunyi"
         * langsung berlaku di folder SAF, bukan hanya di SFTP.
         */
        @JavascriptInterface
        fun localList(requestId: String, uri: String, showHidden: Boolean) {
            runTask(requestId, "localList") { listTree(uri, showHidden) }
        }

        /** Buka file lokal dari folder bookmark: teks ke editor, gambar ke viewer. */
        @JavascriptInterface
        fun localOpen(requestId: String, uriString: String) {
            scope.launch {
                val uri = Uri.parse(uriString)
                val prepared = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = getFileName(uri)
                        val mime = contentResolver.getType(uri)
                        if (isImage(name, mime)) Triple(name, mimeFor(name, mime), readUriBytes(uri))
                        else Triple(name, "text", readUriText(uri).toByteArray(Charsets.UTF_8))
                    }
                }
                prepared.fold(
                    onSuccess = { (name, kind, bytes) ->
                        if (kind == "text") {
                            currentFileUri = uri
                            activeRemotePath = null
                            dispatchLoad(bytes.toString(Charsets.UTF_8), name, null)
                        } else {
                            currentFileUri = null
                            activeRemotePath = null
                            dispatchImage(bytes, kind, name)
                        }
                        emitResult(requestId, "localOpen", true, JSONObject().put("name", name), null)
                    },
                    onFailure = { emitResult(requestId, "localOpen", false, null, it.message ?: "Gagal membuka file") }
                )
            }
        }

        @JavascriptInterface
        fun loadSettings(): String {
            return JSONObject()
                .put("sortAscending", prefs.getBoolean("sortAscending", true))
                .put("showHidden", prefs.getBoolean("showHidden", false))
                .put("autoList", prefs.getBoolean("autoList", true))
                .toString()
        }

        @JavascriptInterface
        fun saveSettings(settingsJson: String) {
            val json = JSONObject(settingsJson)
            prefs.edit()
                .putBoolean("sortAscending", json.optBoolean("sortAscending", true))
                .putBoolean("showHidden", json.optBoolean("showHidden", false))
                .putBoolean("autoList", json.optBoolean("autoList", true))
                .apply()
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }

    private fun hostKeyPref(host: String, port: Int) = "hostkey_${host}_$port"
    private fun trustedFingerprint(host: String, port: Int): String? = prefs.getString(hostKeyPref(host, port), null)

    // Alias generik runSftp — dipakai juga untuk operasi lokal/SAF.
    private fun runTask(requestId: String, action: String, work: () -> Any?) = runSftp(requestId, action, work)

    /** Hasil sinkron untuk @JavascriptInterface: selalu JSON {ok, ...} / {ok:false, error}. */
    private inline fun wrapSync(block: () -> JSONObject): String =
        runCatching { block().put("ok", true) }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "Operasi gagal") }
            .toString()

    private fun authTypeOf(json: JSONObject): String =
        if (json.optString("privateKeyPath").isNotEmpty()) "key" else "password"

    private fun configFromJson(json: JSONObject, fingerprint: String? = null) = SftpManager.Config(
        host = json.getString("host").trim(),
        port = json.optInt("port", 22),
        username = json.getString("username").trim(),
        password = json.optString("password").ifEmpty { null },
        privateKeyPath = json.optString("privateKeyPath").ifEmpty { null },
        passphrase = json.optString("passphrase").ifEmpty { null },
        trustedFingerprint = fingerprint
    )

    private fun savedToConfig(saved: SftpConnectionStore.Saved, fingerprint: String?) = SftpManager.Config(
        host = saved.host,
        port = saved.port,
        username = saved.username,
        password = saved.password,
        privateKeyPath = saved.privateKeyPath,
        passphrase = saved.passphrase,
        trustedFingerprint = fingerprint
    )

    private fun connectOutcome(config: SftpManager.Config, label: String): JSONObject =
        when (val outcome = sftp.connect(config)) {
            is SftpManager.ConnectResult.Connected ->
                JSONObject().put("status", "connected").put("home", outcome.home).put("label", label)
            is SftpManager.ConnectResult.HostKeyRequired ->
                JSONObject().put("status", "hostKey").put("fingerprint", outcome.fingerprint)
        }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico")

        /** requestId tetap untuk hasil Save — WebView mendaftarkan handler dengan id ini. */
        const val SAVE_REQUEST_ID = "save-file"
    }

    /**
     * WebView ini SPA satu halaman, jadi canGoBack() bukan indikator yang benar. Back
     * ditangani lewat OnBackPressedDispatcher (bukan override onBackPressed yang sudah
     * deprecated dan TIDAK dipanggil lagi saat predictive back aktif — itu membuat Back
     * langsung menutup Activity sehingga sesi SFTP terbuang dan user harus reconnect).
     *
     * UI web selalu mendapat kesempatan pertama: menutup dialog/overlay, atau naik satu
     * level folder di explorer memakai koneksi yang masih terbuka.
     */
    private val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!isWebViewReady) {
                finishFromBack()
                return
            }
            // Satu penekanan Back = tepat satu keputusan. Kalau callback JS tidak pernah
            // datang (WebView sibuk/crash renderer), watchdog memastikan Back tidak "mati"
            // dan aplikasi tetap bisa ditutup.
            var decided = false
            val decide = { handled: Boolean ->
                if (!decided) {
                    decided = true
                    if (!handled) finishFromBack()
                }
            }
            val watchdog = Runnable { decide(false) }
            webView.postDelayed(watchdog, 600L)
            webView.evaluateJavascript(
                "(function(){ try { return !!(window.__voidHandleBack && window.__voidHandleBack()); } catch (e) { return false; } })()"
            ) { handled ->
                webView.removeCallbacks(watchdog)
                // Belum ditangani web (tidak ada dialog/panel/riwayat folder) → tutup activity.
                decide(handled == "true")
            }
        }
    }

    /** Keluar via Back: sesi SFTP baru diputus di onDestroy, bukan di sini. */
    private fun finishFromBack() {
        backCallback.isEnabled = false
        finish()
    }
}
