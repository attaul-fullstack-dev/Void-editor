package com.voidedit

import android.annotation.SuppressLint
import android.app.Activity
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var currentFileUri: Uri? = null
    private var isWebViewReady = false
    private var pendingLoadUri: Uri? = null
    private var pendingWriteContent: String? = null

    // Launcher: buka file
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

    // Launcher: save as
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

            val escaped = content
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("\$", "\\\$")
            val escapedName = fileName.replace("\\", "\\\\").replace("`", "\\`")

            webView.post {
                webView.evaluateJavascript("loadFileContent(`$escaped`, `$escapedName`)", null)
            }
        } catch (e: Exception) {
            toast("Gagal buka file: ${e.message}")
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

    inner class AndroidBridge {
        @JavascriptInterface
        fun onSaveRequest(content: String, fileName: String) {
            runOnUiThread {
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
        fun exitApp() {
            runOnUiThread { finish() }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
