package com.voidedit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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

    // (requestId, action) milik pemilihan file yang sedang berjalan. Wajib ada supaya
    // pembatalan picker tetap membalas ke JS — tanpa ini Promise di sisi WebView
    // menggantung selamanya dan overlay "loading" tidak pernah hilang.
    private var pickRequest: Pair<String, String>? = null

    // requestId yang menunggu hasil ACTION_OPEN_DOCUMENT_TREE (Fitur E).
    private var pendingTreeRequestId: String? = null

    private data class PendingSftpDownload(
        val requestId: String,
        val items: List<SftpManager.DownloadItem>,
        val archive: Boolean,
        val fileName: String
    )

    private var pendingSftpDownload: PendingSftpDownload? = null

    private data class LocalDownloadItem(
        val name: String,
        val uri: Uri,
        val directory: Boolean,
        val mime: String?
    )

    private data class LocalDocumentChild(
        val name: String,
        val uri: Uri,
        val directory: Boolean
    )

    private data class PendingLocalDownload(
        val requestId: String,
        val items: List<LocalDownloadItem>,
        val archive: Boolean,
        val fileName: String
    )

    private var pendingLocalDownload: PendingLocalDownload? = null
    private var pendingDownloadStart: (() -> Unit)? = null
    private var downloadReceiverRegistered = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val start = pendingDownloadStart
        pendingDownloadStart = null
        start?.invoke()
    }

    private val downloadEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadService.ACTION_EVENT) return
            val requestId = intent.getStringExtra(DownloadService.EXTRA_TASK_ID) ?: return
            val operation = intent.getStringExtra(DownloadService.EXTRA_OPERATION) ?: "download"
            when (intent.getStringExtra(DownloadService.EXTRA_EVENT_TYPE)) {
                DownloadService.EVENT_PROGRESS -> emitProgress(
                    requestId = requestId,
                    done = intent.getIntExtra(DownloadService.EXTRA_DONE, 0),
                    total = intent.getIntExtra(DownloadService.EXTRA_TOTAL, 0),
                    label = intent.getStringExtra(DownloadService.EXTRA_LABEL).orEmpty(),
                    operation = operation
                )
                DownloadService.EVENT_FINISHED -> {
                    val success = intent.getBooleanExtra(DownloadService.EXTRA_SUCCESS, false)
                    val fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME).orEmpty()
                    val archive = intent.getBooleanExtra(DownloadService.EXTRA_ARCHIVE, false)
                    val error = intent.getStringExtra(DownloadService.EXTRA_ERROR)
                    emitProgress(requestId, 0, 0, "", operation, finished = true)
                    if (success) toast("Download tersimpan: $fileName")
                    emitResult(
                        requestId,
                        operation,
                        success,
                        if (success) JSONObject().put("name", fileName).put("archive", archive) else null,
                        error
                    )
                }
            }
        }
    }

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
        val req = pickRequest
        pickCallback = null
        pickRequest = null
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri != null && cb != null) {
            cb.invoke(uri)
        } else if (req != null) {
            // Dibatalkan (atau tidak ada data): balas gagal supaya Promise di JS selesai.
            emitResult(
                req.first,
                req.second,
                false,
                null,
                if (result.resultCode == Activity.RESULT_OK) "Tidak ada file yang dipilih" else "Dibatalkan"
            )
        }
    }

    /**
     * JSON valid tapi belum tentu JavaScript valid: U+2028 (LINE SEPARATOR) dan
     * U+2029 (PARAGRAPH SEPARATOR) dibiarkan mentah oleh JSONObject, sedangkan di
     * dalam skrip yang dieksekusi evaluateJavascript keduanya dihitung sebagai
     * pemutus baris sehingga literal string jadi rusak dan seluruh pemanggilan
     * gagal senyap. File yang mengandung karakter itu wajib di-escape dulu.
     */
    private fun jsPayload(json: String): String =
        json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /**
     * Membuka picker sambil mencatat (requestId, action) supaya pembatalan selalu
     * dibalas ke JS. Semua alur pilih-file wajib lewat fungsi ini.
     */
    private fun launchPicker(
        requestId: String,
        action: String,
        mimeType: String,
        onPicked: (Uri) -> Unit
    ) {
        pickCallback = onPicked
        pickRequest = requestId to action
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        runCatching { pickFileLauncher.launch(intent) }.onFailure {
            pickCallback = null
            pickRequest = null
            emitResult(requestId, action, false, null, "Tidak ada aplikasi pemilih file")
        }
    }

    private val downloadFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingSftpDownload
        pendingSftpDownload = null
        if (pending == null) return@registerForActivityResult
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri == null) {
            emitResult(pending.requestId, "download", false, null, "Download dibatalkan")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        runDownloadWithNotificationPermission start@{
            val config = sftp.authenticatedConfig()
            if (config == null) {
                discardCreatedOutput(uri)
                emitResult(pending.requestId, "download", false, null, "Koneksi SFTP sudah tidak aktif")
                return@start
            }
            runCatching {
                DownloadService.startSftp(
                    this,
                    pending.requestId,
                    uri,
                    pending.fileName,
                    pending.archive,
                    pending.items,
                    config
                )
            }.onFailure {
                discardCreatedOutput(uri)
                emitResult(pending.requestId, "download", false, null, it.message ?: "Download gagal dimulai")
            }
        }
    }

    private val localDownloadFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingLocalDownload
        pendingLocalDownload = null
        if (pending == null) return@registerForActivityResult
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        if (uri == null) {
            emitResult(pending.requestId, "localDownload", false, null, "Download dibatalkan")
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        runDownloadWithNotificationPermission {
            val itemsJson = JSONArray().apply {
                pending.items.forEach { item ->
                    put(
                        JSONObject()
                            .put("name", item.name)
                            .put("uri", item.uri.toString())
                            .put("directory", item.directory)
                    )
                }
            }.toString()
            runCatching {
                DownloadService.startLocal(
                    this,
                    pending.requestId,
                    uri,
                    pending.fileName,
                    pending.archive,
                    itemsJson
                )
            }.onFailure {
                discardCreatedOutput(uri)
                emitResult(
                    pending.requestId,
                    "localDownload",
                    false,
                    null,
                    it.message ?: "Download lokal gagal dimulai"
                )
            }
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

        ContextCompat.registerReceiver(
            this,
            downloadEvents,
            IntentFilter(DownloadService.ACTION_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        downloadReceiverRegistered = true

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
        if (downloadReceiverRegistered) {
            runCatching { unregisterReceiver(downloadEvents) }
            downloadReceiverRegistered = false
        }
        // Download berjalan di DownloadService dan tidak dibatalkan bersama Activity.
        scope.cancel()
        runCatching { sftp.disconnect() }
        // WebView memegang referensi ke Activity; tanpa destroy() proses renderer dan
        // Activity ikut tertahan (memory leak) setiap kali Activity dibuat ulang.
        runCatching {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
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
            webView.evaluateJavascript("window.__voidLoadImage && window.__voidLoadImage(${jsPayload(payload)})", null)
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

    /**
     * ACTION_OPEN_DOCUMENT_TREE menghasilkan tree URI. URI itu cukup untuk listing,
     * tetapi operasi tulis DocumentsContract membutuhkan parent document URI.
     * Subfolder dari listTree sudah berupa document URI dan dipertahankan apa adanya.
     */
    private fun writableDocumentUri(uri: Uri): Uri =
        if (DocumentsContract.isDocumentUri(this, uri)) uri
        else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))

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

    /** Hapus isi folder SAF dari level terdalam sebelum menghapus folder induknya. */
    private fun deleteDocumentRecursively(uri: Uri, directoryHint: Boolean? = null) {
        val isDirectory = directoryHint
            ?: (contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR)
        if (isDirectory) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentIdOf(uri))
            val children = mutableListOf<Pair<Uri, Boolean>>()
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val mime = cursor.getString(1)
                    children += DocumentsContract.buildDocumentUriUsingTree(uri, documentId) to
                        (mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            } ?: error("Isi folder tidak dapat dibaca")
            children.forEach { (childUri, childIsDirectory) ->
                deleteDocumentRecursively(childUri, childIsDirectory)
            }
        }
        check(DocumentsContract.deleteDocument(contentResolver, uri)) { "Item tidak dapat dihapus" }
        if (currentFileUri == uri) runOnUiThread {
            if (currentFileUri == uri) currentFileUri = null
        }
    }

    private fun validateDocumentName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/')) { "Nama tidak valid" }
    }

    private fun createDocument(parent: Uri, name: String, directory: Boolean): Uri {
        validateDocumentName(name)
        val mime = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
        return DocumentsContract.createDocument(contentResolver, writableDocumentUri(parent), mime, name)
            ?: error("Item tidak dapat dibuat")
    }

    private fun copyUriToFolder(source: Uri, parent: Uri): Uri {
        val name = getFileName(source)
        validateDocumentName(name)
        val mime = contentResolver.getType(source) ?: "application/octet-stream"
        val target = DocumentsContract.createDocument(contentResolver, writableDocumentUri(parent), mime, name)
            ?: error("File tidak dapat dibuat")
        try {
            contentResolver.openInputStream(source)?.use { input ->
                contentResolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output) }
                    ?: error("File tujuan tidak dapat ditulis")
            } ?: error("File sumber tidak dapat dibaca")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            throw error
        }
        return target
    }

    private fun copyFileTreeToSaf(source: File, parent: Uri) {
        source.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
            validateDocumentName(child.name)
            if (child.isDirectory) {
                val directory = createDocument(parent, child.name, true)
                copyFileTreeToSaf(child, directory)
            } else {
                val target = DocumentsContract.createDocument(
                    contentResolver, writableDocumentUri(parent), "application/octet-stream", child.name
                ) ?: error("Gagal membuat ${child.name}")
                contentResolver.openOutputStream(target, "w")?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Gagal menulis ${child.name}")
            }
        }
    }

    // Kirim konten file ke editor via JSON agar aman terhadap backtick/backslash/Unicode.
    private fun dispatchLoad(content: String, name: String, remotePath: String?) {
        val payload = JSONObject()
            .put("content", content)
            .put("name", name)
            .put("remotePath", remotePath ?: JSONObject.NULL)
            .toString()
        webView.post {
            webView.evaluateJavascript("window.__voidLoadFile && window.__voidLoadFile(${jsPayload(payload)})", null)
        }
    }

    private fun getFileName(uri: Uri): String {
        // getString() boleh mengembalikan null walau kolomnya ada (mis. provider yang
        // tidak mengisi DISPLAY_NAME) — menugaskannya ke String non-null memicu NPE.
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) {
                        val value = cursor.getString(col)?.trim()
                        if (!value.isNullOrEmpty()) return value
                    }
                }
            } catch (_: Exception) {}
        }
        // Fallback untuk file:// dan content:// tanpa DISPLAY_NAME. Tanpa ini, membuka
        // file lewat ACTION_VIEW dari file manager selalu bernama "untitled.txt".
        val segment = uri.lastPathSegment?.substringAfterLast('/')?.trim()
        if (!segment.isNullOrEmpty()) return segment
        return "untitled.txt"
    }

    private fun safeSuggestedName(raw: String, fallback: String): String =
        raw.trim()
            .map { if (it.code < 32 || it.code == 47 || it.code == 92) "_" else it.toString() }
            .joinToString("")
            .take(120)
            .ifBlank { fallback }

    private fun safeLocalZipSegment(raw: String): String {
        val cleaned = raw.map {
            if (it.code < 32 || it.code == 47 || it.code == 92) "_" else it.toString()
        }.joinToString("").take(255).ifBlank { "item" }
        return if (cleaned == "." || cleaned == "..") "_" + cleaned else cleaned
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

    private fun runDownloadWithNotificationPermission(start: () -> Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadStart = start
            runCatching {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }.onFailure {
                pendingDownloadStart = null
                start()
            }
        } else {
            start()
        }
    }

    private fun discardCreatedOutput(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

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

    private fun writeDownloadToUri(uri: Uri, writer: (java.io.OutputStream) -> Unit) {
        val direct = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
        if (direct != null) {
            direct.use(writer)
            return
        }
        contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
            java.io.FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.truncate(0)
                writer(stream)
                stream.flush()
            }
        } ?: error("Lokasi download tidak dapat ditulis")
    }

    private fun listLocalDownloadChildren(parent: Uri): List<LocalDocumentChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentIdOf(parent))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val children = mutableListOf<LocalDocumentChild>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: documentId.substringAfterLast("/")
                val mime = cursor.getString(2)
                children += LocalDocumentChild(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(parent, documentId),
                    directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        } ?: error("Isi folder lokal tidak dapat dibaca")
        return children
    }

    private fun copyLocalDocument(uri: Uri, output: java.io.OutputStream) {
        contentResolver.openInputStream(uri)?.use { input ->
            input.copyTo(output, DEFAULT_BUFFER_SIZE)
        } ?: error("File lokal tidak dapat dibaca")
    }

    private fun writeLocalZipSource(
        uri: Uri,
        entryName: String,
        directoryHint: Boolean?,
        zip: ZipOutputStream,
        depth: Int,
        onEntry: (String, Boolean) -> Unit
    ) {
        require(depth <= MAX_LOCAL_DOWNLOAD_DEPTH) { "Folder terlalu dalam untuk dijadikan ZIP" }
        val directory = directoryHint
            ?: (contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR)
        val zipName = if (directory) entryName.removeSuffix("/") + "/" else entryName
        onEntry(zipName, false)
        zip.putNextEntry(ZipEntry(zipName))
        if (!directory) copyLocalDocument(uri, zip)
        zip.closeEntry()
        onEntry(zipName, true)
        if (!directory) return
        listLocalDownloadChildren(uri).forEach { child ->
            writeLocalZipSource(
                uri = child.uri,
                entryName = entryName + "/" + safeLocalZipSegment(child.name),
                directoryHint = child.directory,
                zip = zip,
                depth = depth + 1,
                onEntry = onEntry
            )
        }
    }

    private fun performLocalDownload(pending: PendingLocalDownload, uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var lastProgressAt = 0L
                    writeDownloadToUri(uri) { output ->
                        if (pending.archive) {
                            var completed = 0
                            ZipOutputStream(output.buffered()).use { zip ->
                                pending.items.forEach { item ->
                                    writeLocalZipSource(
                                        uri = item.uri,
                                        entryName = safeLocalZipSegment(item.name),
                                        directoryHint = item.directory,
                                        zip = zip,
                                        depth = 0,
                                        onEntry = { label, finished ->
                                            if (finished) completed += 1
                                            val now = System.currentTimeMillis()
                                            if (lastProgressAt == 0L || now - lastProgressAt >= 250L) {
                                                lastProgressAt = now
                                                emitProgress(
                                                    pending.requestId,
                                                    completed,
                                                    0,
                                                    label,
                                                    "localDownload"
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                            emitProgress(
                                pending.requestId,
                                completed,
                                completed,
                                "Selesai",
                                "localDownload"
                            )
                        } else {
                            emitProgress(pending.requestId, 0, 1, pending.fileName, "localDownload")
                            copyLocalDocument(pending.items.single().uri, output)
                            emitProgress(pending.requestId, 1, 1, pending.fileName, "localDownload")
                        }
                    }
                }
            }
            emitProgress(pending.requestId, 0, 0, "", "localDownload", finished = true)
            result.fold(
                onSuccess = {
                    toast("Download tersimpan: " + pending.fileName)
                    emitResult(
                        pending.requestId,
                        "localDownload",
                        true,
                        JSONObject().put("name", pending.fileName).put("archive", pending.archive),
                        null
                    )
                },
                onFailure = {
                    emitResult(
                        pending.requestId,
                        "localDownload",
                        false,
                        null,
                        it.message ?: "Download lokal gagal"
                    )
                }
            )
        }
    }

    private fun performSftpDownload(pending: PendingSftpDownload, uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val transfer = SftpManager()
                    try {
                        val config = sftp.authenticatedConfig()
                            ?: error("Koneksi SFTP sudah tidak aktif")
                        when (transfer.connect(config)) {
                            is SftpManager.ConnectResult.Connected -> Unit
                            is SftpManager.ConnectResult.HostKeyRequired ->
                                error("Fingerprint koneksi download belum dipercaya")
                        }
                        var lastProgressAt = 0L
                        writeDownloadToUri(uri) { output ->
                            if (pending.archive) {
                                transfer.downloadZip(pending.items, output) { done, total, label ->
                                    val now = System.currentTimeMillis()
                                    if (total > 0 || lastProgressAt == 0L || now - lastProgressAt >= 250L) {
                                        lastProgressAt = now
                                        emitProgress(pending.requestId, done, total, label, "download")
                                    }
                                }
                            } else {
                                transfer.downloadFile(pending.items.single().path, output)
                            }
                        }
                    } finally {
                        transfer.disconnect()
                    }
                }
            }
            emitProgress(pending.requestId, 0, 0, "", "download", finished = true)
            result.fold(
                onSuccess = {
                    toast("Download tersimpan: " + pending.fileName)
                    emitResult(
                        pending.requestId,
                        "download",
                        true,
                        JSONObject().put("name", pending.fileName).put("archive", pending.archive),
                        null
                    )
                },
                onFailure = {
                    emitResult(
                        pending.requestId,
                        "download",
                        false,
                        null,
                        it.message ?: "Download gagal"
                    )
                }
            )
        }
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
            webView.evaluateJavascript("window.onSftpResult && window.onSftpResult(${jsPayload(payload)})", null)
        }
    }

    private fun emitProgress(
        requestId: String,
        done: Int,
        total: Int,
        label: String,
        operation: String = "upload",
        finished: Boolean = false
    ) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("done", done)
            .put("total", total)
            .put("label", label)
            .put("operation", operation)
            .put("finished", finished)
            .toString()
        webView.post {
            webView.evaluateJavascript("window.onSftpProgress && window.onSftpProgress(${jsPayload(payload)})", null)
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
        fun sftpDownload(requestId: String, itemsJson: String, archiveName: String) {
            val parsed = runCatching {
                val array = JSONArray(itemsJson)
                require(array.length() in 1..500) { "Pilih antara 1 sampai 500 item" }
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val path = item.getString("path")
                    require(path.isNotBlank()) { "Path download tidak valid" }
                    SftpManager.DownloadItem(
                        name = safeSuggestedName(item.optString("name"), "item"),
                        path = path,
                        directory = item.optBoolean("directory", false)
                    )
                }
            }
            if (parsed.isFailure) {
                emitResult(
                    requestId,
                    "download",
                    false,
                    null,
                    parsed.exceptionOrNull()?.message ?: "Pilihan download tidak valid"
                )
                return
            }
            val items = parsed.getOrThrow()
            val archive = items.size > 1 || items.single().directory
            val suggestedName = if (archive) {
                val base = safeSuggestedName(archiveName.removeSuffix(".zip"), "voidedit-download")
                base + ".zip"
            } else items.single().name
            runOnUiThread {
                if (pendingSftpDownload != null) {
                    emitResult(requestId, "download", false, null, "Pemilih lokasi download masih terbuka")
                    return@runOnUiThread
                }
                pendingSftpDownload = PendingSftpDownload(requestId, items, archive, suggestedName)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (archive) "application/zip" else "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, suggestedName)
                }
                runCatching { downloadFileLauncher.launch(intent) }.onFailure { error ->
                    pendingSftpDownload = null
                    emitResult(
                        requestId,
                        "download",
                        false,
                        null,
                        error.message ?: "Pemilih lokasi download tidak tersedia"
                    )
                }
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
                launchPicker(requestId, "upload", "*/*") { uri ->
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
            }
        }

        // Pilih ZIP, ekstrak aman ke cache, unggah tree ke remote, lalu bersihkan.
        @JavascriptInterface
        fun sftpImportZip(requestId: String, remoteDir: String) {
            runOnUiThread {
                launchPicker(requestId, "importZip", "application/zip") { uri ->
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
            }
        }

        @JavascriptInterface
        fun sftpPickPrivateKey(requestId: String) {
            runOnUiThread {
                launchPicker(requestId, "pickKey", "*/*") { uri ->
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
            val saved = connectionStore.get(id) ?: error("Koneksi tersimpan tidak ditemukan")
            connectionStore.delete(id)
            prefs.edit().remove(hostKeyPref(saved.host, saved.port)).apply()
            JSONObject().put("id", id).put("fingerprintCleared", true)
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

        @JavascriptInterface
        fun localCreate(requestId: String, parentUri: String, name: String, directory: Boolean) {
            runTask(requestId, "localCreate") {
                val uri = createDocument(Uri.parse(parentUri), name.trim(), directory)
                JSONObject().put("uri", uri.toString())
            }
        }

        @JavascriptInterface
        fun localRename(requestId: String, uriString: String, newName: String) {
            runTask(requestId, "localRename") {
                validateDocumentName(newName.trim())
                val renamed = DocumentsContract.renameDocument(contentResolver, Uri.parse(uriString), newName.trim())
                    ?: error("Item tidak dapat diubah namanya")
                JSONObject().put("uri", renamed.toString())
            }
        }

        @JavascriptInterface
        fun localDelete(requestId: String, uriString: String) {
            runTask(requestId, "localDelete") {
                val uri = Uri.parse(uriString)
                deleteDocumentRecursively(uri)
                JSONObject().put("uri", uriString)
            }
        }

        @JavascriptInterface
        fun localDownload(requestId: String, itemsJson: String, archiveName: String) {
            val parsed = runCatching {
                val array = JSONArray(itemsJson)
                require(array.length() in 1..500) { "Pilih antara 1 sampai 500 item" }
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val uri = Uri.parse(item.getString("uri"))
                    require(uri.scheme == "content") { "URI download lokal tidak valid" }
                    LocalDownloadItem(
                        name = safeSuggestedName(item.optString("name"), "item"),
                        uri = uri,
                        directory = item.optBoolean("directory", false),
                        mime = item.optString("mime").ifBlank { null }
                    )
                }
            }
            if (parsed.isFailure) {
                emitResult(
                    requestId,
                    "localDownload",
                    false,
                    null,
                    parsed.exceptionOrNull()?.message ?: "Pilihan download lokal tidak valid"
                )
                return
            }
            val items = parsed.getOrThrow()
            val archive = items.size > 1 || items.single().directory
            val suggestedName = if (archive) {
                val base = safeSuggestedName(archiveName.removeSuffix(".zip"), "voidedit-local-download")
                base + ".zip"
            } else items.single().name
            runOnUiThread {
                if (pendingLocalDownload != null) {
                    emitResult(requestId, "localDownload", false, null, "Pemilih lokasi download masih terbuka")
                    return@runOnUiThread
                }
                pendingLocalDownload = PendingLocalDownload(requestId, items, archive, suggestedName)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (archive) "application/zip"
                        else items.single().mime ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, suggestedName)
                }
                runCatching { localDownloadFileLauncher.launch(intent) }.onFailure { error ->
                    pendingLocalDownload = null
                    emitResult(
                        requestId,
                        "localDownload",
                        false,
                        null,
                        error.message ?: "Pemilih lokasi download tidak tersedia"
                    )
                }
            }
        }

        @JavascriptInterface
        fun localUpload(requestId: String, parentUri: String) {
            runOnUiThread {
                launchPicker(requestId, "localUpload", "*/*") { source ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { copyUriToFolder(source, Uri.parse(parentUri)) }
                        }
                        result.fold(
                            onSuccess = { emitResult(requestId, "localUpload", true, JSONObject().put("uri", it.toString()), null) },
                            onFailure = { emitResult(requestId, "localUpload", false, null, it.message ?: "Upload gagal") }
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun localImportZip(requestId: String, parentUri: String) {
            runOnUiThread {
                launchPicker(requestId, "localImportZip", "application/zip") { source ->
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val workDir = File(cacheDir, "local_zip_${System.currentTimeMillis()}")
                                workDir.mkdirs()
                                try {
                                    extractZip(source, workDir)
                                    copyFileTreeToSaf(workDir, Uri.parse(parentUri))
                                } finally {
                                    workDir.deleteRecursively()
                                }
                                JSONObject().put("uri", parentUri)
                            }
                        }
                        result.fold(
                            onSuccess = { emitResult(requestId, "localImportZip", true, it, null) },
                            onFailure = { emitResult(requestId, "localImportZip", false, null, it.message ?: "Import ZIP gagal") }
                        )
                    }
                }
            }
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
        const val MAX_LOCAL_DOWNLOAD_DEPTH = 256
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
