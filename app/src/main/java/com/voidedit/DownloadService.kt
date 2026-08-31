package com.voidedit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadService : Service() {
    private data class LocalItem(val name: String, val uri: Uri, val directory: Boolean)
    private data class LocalChild(val name: String, val uri: Uri, val directory: Boolean)
    private data class ActiveTask(
        val id: String,
        val operation: String,
        val fileName: String,
        val notificationId: Int,
        val job: Job
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, ActiveTask>()
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_TASK_ID)?.let { tasks[it]?.job?.cancel() }
            ACTION_START_SFTP, ACTION_START_LOCAL -> startTask(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTask(intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val operation = intent.getStringExtra(EXTRA_OPERATION) ?: return
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "download"
        val outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI)?.let(Uri::parse) ?: return
        if (tasks.containsKey(taskId)) return
        val notificationId = NOTIFICATION_BASE + (taskId.hashCode() and 0x0fffffff)

        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null
            try {
                val checkCancelled = { coroutineContext.ensureActive() }
                when (intent.action) {
                    ACTION_START_SFTP -> performSftp(intent, outputUri, taskId, checkCancelled)
                    ACTION_START_LOCAL -> performLocal(intent, outputUri, taskId, checkCancelled)
                }
                coroutineContext.ensureActive()
            } catch (error: Throwable) {
                failure = error
            }
            val cancelled = failure is CancellationException || !coroutineContext.isActive
            if (failure != null) removePartialOutput(outputUri)
            sendFinished(
                taskId,
                operation,
                fileName,
                intent.getBooleanExtra(EXTRA_ARCHIVE, false),
                failure,
                cancelled
            )
            showFinishedNotification(notificationId, fileName, failure, cancelled)
            tasks.remove(taskId)
            promoteRemainingTaskOrStop()
        }

        val task = ActiveTask(taskId, operation, fileName, notificationId, job)
        tasks[taskId] = task
        startForeground(notificationId, progressNotification(task, 0, 0, "Menyiapkan download"))
        job.start()
    }

    private fun performSftp(
        intent: Intent,
        outputUri: Uri,
        taskId: String,
        checkCancelled: () -> Unit
    ) {
        val json = JSONObject(intent.getStringExtra(EXTRA_CONFIG_JSON) ?: error("Konfigurasi SFTP hilang"))
        val config = SftpManager.Config(
            host = json.getString("host"),
            port = json.getInt("port"),
            username = json.getString("username"),
            password = json.optString("password").ifBlank { null },
            privateKeyPath = json.optString("privateKeyPath").ifBlank { null },
            passphrase = json.optString("passphrase").ifBlank { null },
            trustedFingerprint = json.optString("trustedFingerprint").ifBlank { null }
        )
        val array = JSONArray(intent.getStringExtra(EXTRA_ITEMS_JSON) ?: error("Daftar download hilang"))
        val items = (0 until array.length()).map { index ->
            array.getJSONObject(index).let {
                SftpManager.DownloadItem(
                    name = it.getString("name"),
                    path = it.getString("path"),
                    directory = it.optBoolean("directory")
                )
            }
        }
        val transfer = SftpManager()
        try {
            when (transfer.connect(config)) {
                is SftpManager.ConnectResult.Connected -> Unit
                is SftpManager.ConnectResult.HostKeyRequired ->
                    error("Fingerprint koneksi download belum dipercaya")
            }
            writeDownloadToUri(outputUri) { output ->
                if (intent.getBooleanExtra(EXTRA_ARCHIVE, false)) {
                    var lastProgressAt = 0L
                    transfer.downloadZip(items, output, checkCancelled) { done, total, label ->
                        val now = System.currentTimeMillis()
                        if (total > 0 || lastProgressAt == 0L || now - lastProgressAt >= 250L) {
                            lastProgressAt = now
                            reportProgress(taskId, done, total, label)
                        }
                    }
                } else {
                    reportProgress(taskId, 0, 0, items.single().name)
                    transfer.downloadFile(items.single().path, output, checkCancelled)
                }
            }
        } finally {
            transfer.disconnect()
        }
    }

    private fun performLocal(
        intent: Intent,
        outputUri: Uri,
        taskId: String,
        checkCancelled: () -> Unit
    ) {
        val array = JSONArray(intent.getStringExtra(EXTRA_ITEMS_JSON) ?: error("Daftar download hilang"))
        val items = (0 until array.length()).map { index ->
            array.getJSONObject(index).let {
                LocalItem(it.getString("name"), Uri.parse(it.getString("uri")), it.optBoolean("directory"))
            }
        }
        writeDownloadToUri(outputUri) { output ->
            if (intent.getBooleanExtra(EXTRA_ARCHIVE, false)) {
                var completed = 0
                var lastProgressAt = 0L
                val usedPaths = mutableSetOf<String>()
                ZipOutputStream(output.buffered()).use { zip ->
                    items.forEach { item ->
                        checkCancelled()
                        writeLocalZipSource(
                            item.uri,
                            safeZipSegment(item.name),
                            item.directory,
                            zip,
                            0,
                            usedPaths,
                            checkCancelled
                        ) { label, finished ->
                            if (finished) completed += 1
                            val now = System.currentTimeMillis()
                            if (lastProgressAt == 0L || now - lastProgressAt >= 250L) {
                                lastProgressAt = now
                                reportProgress(taskId, completed, 0, label)
                            }
                        }
                    }
                }
                reportProgress(taskId, completed, completed, "Selesai")
            } else {
                reportProgress(taskId, 0, 0, items.single().name)
                copyLocalDocument(items.single().uri, output, checkCancelled)
            }
        }
    }

    private fun writeDownloadToUri(uri: Uri, writer: (java.io.OutputStream) -> Unit) {
        val direct = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
        if (direct != null) {
            direct.use(writer)
            return
        }
        contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.truncate(0)
                writer(stream)
                stream.flush()
            }
        } ?: error("Lokasi download tidak dapat ditulis")
    }

    private fun listLocalChildren(parent: Uri): List<LocalChild> {
        val documentId = if (DocumentsContract.isDocumentUri(this, parent)) {
            DocumentsContract.getDocumentId(parent)
        } else {
            DocumentsContract.getTreeDocumentId(parent)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val children = mutableListOf<LocalChild>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idColumn) ?: continue
                val mime = cursor.getString(mimeColumn)
                children += LocalChild(
                    cursor.getString(nameColumn) ?: documentId.substringAfterLast('/'),
                    DocumentsContract.buildDocumentUriUsingTree(parent, documentId),
                    mime == DocumentsContract.Document.MIME_TYPE_DIR
                )
            }
        } ?: error("Isi folder lokal tidak dapat dibaca")
        return children
    }

    private fun copyLocalDocument(
        uri: Uri,
        output: java.io.OutputStream,
        checkCancelled: () -> Unit
    ) {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } ?: error("File lokal tidak dapat dibaca")
    }

    private fun writeLocalZipSource(
        uri: Uri,
        entryName: String,
        directory: Boolean,
        zip: ZipOutputStream,
        depth: Int,
        usedPaths: MutableSet<String>,
        checkCancelled: () -> Unit,
        onEntry: (String, Boolean) -> Unit
    ) {
        checkCancelled()
        require(depth <= MAX_DOWNLOAD_DEPTH) { "Folder terlalu dalam untuk dijadikan ZIP" }
        val uniqueEntryName = SftpManager.uniqueZipPath(entryName, usedPaths)
        val zipName = if (directory) uniqueEntryName + "/" else uniqueEntryName
        onEntry(zipName, false)
        zip.putNextEntry(ZipEntry(zipName))
        if (!directory) copyLocalDocument(uri, zip, checkCancelled)
        zip.closeEntry()
        onEntry(zipName, true)
        if (!directory) return
        listLocalChildren(uri).forEach { child ->
            writeLocalZipSource(
                child.uri,
                uniqueEntryName + "/" + safeZipSegment(child.name),
                child.directory,
                zip,
                depth + 1,
                usedPaths,
                checkCancelled,
                onEntry
            )
        }
    }

    private fun reportProgress(taskId: String, done: Int, total: Int, label: String) {
        val task = tasks[taskId] ?: return
        notifications.notify(task.notificationId, progressNotification(task, done, total, label))
        sendBroadcast(
            Intent(ACTION_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_TYPE, EVENT_PROGRESS)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_OPERATION, task.operation)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_LABEL, label)
        )
    }

    private fun sendFinished(
        taskId: String,
        operation: String,
        fileName: String,
        archive: Boolean,
        failure: Throwable?,
        cancelled: Boolean
    ) {
        val error = when {
            cancelled -> "Download dibatalkan"
            failure != null -> failure.message ?: "Download gagal"
            else -> null
        }
        sendBroadcast(
            Intent(ACTION_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_TYPE, EVENT_FINISHED)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_OPERATION, operation)
                .putExtra(EXTRA_FILE_NAME, fileName)
                .putExtra(EXTRA_ARCHIVE, archive)
                .putExtra(EXTRA_SUCCESS, failure == null && !cancelled)
                .putExtra(EXTRA_ERROR, error)
        )
    }

    private fun progressNotification(task: ActiveTask, done: Int, total: Int, label: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mengunduh ${task.fileName}")
            .setContentText(label.ifBlank { "Sedang berjalan" })
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0) total else 0, done, total <= 0)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, "Batal", cancelPendingIntent(task.id))
            .build()

    private fun showFinishedNotification(
        notificationId: Int,
        fileName: String,
        failure: Throwable?,
        cancelled: Boolean
    ) {
        val title = when {
            cancelled -> "Download dibatalkan"
            failure != null -> "Download gagal"
            else -> "Download selesai"
        }
        notifications.notify(
            notificationId,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(
                    if (failure == null && !cancelled) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error
                )
                .setContentTitle(title)
                .setContentText(failure?.message ?: fileName)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build()
        )
    }

    private fun cancelPendingIntent(taskId: String): PendingIntent = PendingIntent.getService(
        this,
        taskId.hashCode(),
        Intent(this, DownloadService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun promoteRemainingTaskOrStop() {
        val remaining = tasks.values.firstOrNull()
        if (remaining == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
            else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelf()
        } else {
            startForeground(
                remaining.notificationId,
                progressNotification(remaining, 0, 0, "Download masih berjalan")
            )
        }
    }

    private fun removePartialOutput(uri: Uri) {
        val deleted = runCatching {
            DocumentsContract.deleteDocument(contentResolver, uri)
        }.getOrDefault(false)
        if (deleted) return
        runCatching {
            contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).channel.use { it.truncate(0) }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Download file", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress download file dan folder VoidEdit"
            }
        )
    }

    private fun safeZipSegment(name: String): String {
        val cleaned = name.map {
            when {
                it == '/' || it == '\\' -> '_'
                it.code < 32 -> '_'
                else -> it
            }
        }.joinToString("").trim()
        return cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "item"
    }

    companion object {
        const val ACTION_EVENT = "com.voidedit.DOWNLOAD_EVENT"
        const val EXTRA_EVENT_TYPE = "eventType"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_ARCHIVE = "archive"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_ERROR = "error"
        const val EXTRA_DONE = "done"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_LABEL = "label"
        const val EVENT_PROGRESS = "progress"
        const val EVENT_FINISHED = "finished"

        private const val ACTION_START_SFTP = "com.voidedit.START_SFTP_DOWNLOAD"
        private const val ACTION_START_LOCAL = "com.voidedit.START_LOCAL_DOWNLOAD"
        private const val ACTION_CANCEL = "com.voidedit.CANCEL_DOWNLOAD"
        private const val EXTRA_OUTPUT_URI = "outputUri"
        private const val EXTRA_ITEMS_JSON = "itemsJson"
        private const val EXTRA_CONFIG_JSON = "configJson"
        private const val CHANNEL_ID = "voidedit_downloads"
        private const val NOTIFICATION_BASE = 20_000
        private const val MAX_DOWNLOAD_DEPTH = 256

        fun startSftp(
            context: Context,
            requestId: String,
            outputUri: Uri,
            fileName: String,
            archive: Boolean,
            items: List<SftpManager.DownloadItem>,
            config: SftpManager.Config
        ) {
            val itemsJson = JSONArray().apply {
                items.forEach {
                    put(JSONObject().put("name", it.name).put("path", it.path).put("directory", it.directory))
                }
            }
            val configJson = JSONObject()
                .put("host", config.host)
                .put("port", config.port)
                .put("username", config.username)
                .put("password", config.password ?: "")
                .put("privateKeyPath", config.privateKeyPath ?: "")
                .put("passphrase", config.passphrase ?: "")
                .put("trustedFingerprint", config.trustedFingerprint ?: "")
            start(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_START_SFTP)
                    .putExtra(EXTRA_CONFIG_JSON, configJson.toString())
                    .putExtra(EXTRA_ITEMS_JSON, itemsJson.toString())
                    .putCommon(requestId, "download", outputUri, fileName, archive)
            )
        }

        fun startLocal(
            context: Context,
            requestId: String,
            outputUri: Uri,
            fileName: String,
            archive: Boolean,
            itemsJson: String
        ) {
            start(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_START_LOCAL)
                    .putExtra(EXTRA_ITEMS_JSON, itemsJson)
                    .putCommon(requestId, "localDownload", outputUri, fileName, archive)
            )
        }

        private fun Intent.putCommon(
            requestId: String,
            operation: String,
            outputUri: Uri,
            fileName: String,
            archive: Boolean
        ): Intent = putExtra(EXTRA_TASK_ID, requestId)
            .putExtra(EXTRA_OPERATION, operation)
            .putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
            .putExtra(EXTRA_FILE_NAME, fileName)
            .putExtra(EXTRA_ARCHIVE, archive)

        private fun start(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
