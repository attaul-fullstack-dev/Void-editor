package com.voidedit

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentFileUri: Uri? = null
    private var isWebViewReady = false
    private var pendingLoadUri: Uri? = null
    private var pendingWriteContent: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sftp = SftpManager()
    private val prefs by lazy { getSharedPreferences("void_sftp", Context.MODE_PRIVATE) }

    // Remote path yang sedang dibuka. Jika tidak null, tombol Save menulis ke server.
    private var activeRemotePath: String? = null

    // Launcher pemilihan file yang menyalurkan hasil ke handler dinamis.
    private var pickCallback: ((Uri) -> Unit)? = null

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
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                currentFileUri = uri
                pendingWriteContent?.let { writeToUri(uri, it) }
                pendingWriteContent = null
            }
        }
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        webView.loadUrl("file:///android_asset/voidedit.html")
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
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
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.readText() ?: return
            currentFileUri = uri
            activeRemotePath = null
            dispatchLoad(content, fileName, null)
        } catch (e: Exception) {
            toast("Gagal buka file: ${e.message}")
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

    private fun writeToUri(uri: Uri, content: String) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            }
            toast("✓ Tersimpan")
        } catch (e: Exception) {
            toast("Gagal simpan: ${e.message}")
        }
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
        @JavascriptInterface
        fun onSaveRequest(content: String, fileName: String) {
            runOnUiThread {
                val remote = activeRemotePath
                if (remote != null) {
                    runSftp("save-remote", "write") {
                        sftp.write(remote, content); JSONObject().put("path", remote)
                    }
                    return@runOnUiThread
                }
                val uri = currentFileUri
                if (uri != null) writeToUri(uri, content)
                else {
                    pendingWriteContent = content
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, fileName)
                    }
                    saveAsLauncher.launch(intent)
                }
            }
        }

        @JavascriptInterface
        fun sftpConnect(requestId: String, configJson: String) {
            val json = JSONObject(configJson)
            runSftp(requestId, "connect") {
                val config = SftpManager.Config(
                    host = json.getString("host").trim(),
                    port = json.optInt("port", 22),
                    username = json.getString("username").trim(),
                    password = json.optString("password").ifEmpty { null },
                    privateKeyPath = json.optString("privateKeyPath").ifEmpty { null },
                    passphrase = json.optString("passphrase").ifEmpty { null },
                    trustedFingerprint = trustedFingerprint(json.getString("host").trim(), json.optInt("port", 22))
                )
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
