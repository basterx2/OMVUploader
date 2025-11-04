package com.example.omvuploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager
    private var uploadJob: Job? = null
    private var isCancelled = false

    companion object {
        private const val CHANNEL_ID = "upload_channel"
        private const val NOTIFICATION_ID = 1

        fun startUpload(context: Context, uris: ArrayList<Uri>, uploadPath: String) {
            val intent = Intent(context, UploadService::class.java).apply {
                putParcelableArrayListExtra("uris", uris)
                putExtra("uploadPath", uploadPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CANCEL_UPLOAD" -> {
                isCancelled = true
                uploadJob?.cancel()
                showCancelledNotification()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val uris = intent?.getParcelableArrayListExtra<Uri>("uris") ?: arrayListOf()
                val uploadPath = intent?.getStringExtra("uploadPath") ?: ""

                startForeground(NOTIFICATION_ID, createNotification(0, uris.size))

                uploadJob = serviceScope.launch {
                    uploadFiles(uris, uploadPath)
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun uploadFiles(uris: List<Uri>, uploadPath: String) {
        try {
            val credentialsManager = CredentialsManager(this)
            val credentials = credentialsManager.getCredentials()

            if (credentials != null) {
                val smbManager = SMBManager(
                    credentials.server,
                    credentials.username,
                    credentials.password,
                    credentials.shareName ?: "RaspberryHDD"
                )
                smbManager.initialize()

                val totalFiles = uris.size
                uris.forEachIndexed { index, uri ->
                    if (isCancelled) {
                        return@forEachIndexed
                    }

                    val fileName = getFileName(uri)

                    // Progreso del archivo en etapas
                    for (progress in listOf(0, 33, 66, 100)) {
                        if (isCancelled) break

                        updateNotification(index + 1, totalFiles, fileName, progress)
                        if (progress < 100) {
                            kotlinx.coroutines.delay(300)
                        }
                    }

                    if (!isCancelled) {
                        val inputStream = contentResolver.openInputStream(uri)
                        inputStream?.use { stream ->
                            smbManager.uploadFile(stream, fileName, uploadPath)
                        }

                        // Notificar que archivo fue completado
                        val completeIntent = Intent("com.example.omvuploader.FILE_UPLOADED").apply {
                            putExtra("uri", uri.toString())
                        }
                        sendBroadcast(completeIntent)
                    }
                }

                if (!isCancelled) {
                    showCompletionNotification(totalFiles)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isCancelled) {
                showErrorNotification(e.message)
            }
        } finally {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Subida de Archivos",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra el progreso de subida de archivos al servidor"
                setShowBadge(true)
                enableLights(true)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(current: Int, total: Int): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, UploadService::class.java).apply {
            action = "CANCEL_UPLOAD"
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📤 Subiendo archivos")
            .setContentText("Preparando...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(total, current, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Cancelar", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(current: Int, total: Int, fileName: String, fileProgress: Int = 0) {
        val cancelIntent = Intent(this, UploadService::class.java).apply {
            action = "CANCEL_UPLOAD"
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val progressText = if (fileProgress > 0) {
            "$fileName - $fileProgress%\n$current/$total archivos completados"
        } else {
            "$fileName\n$current/$total archivos"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📤 Subiendo archivos ($current/$total)")
            .setContentText(progressText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, fileProgress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Cancelar", cancelPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Enviar broadcast para actualizar la UI
        val intent = Intent("com.example.omvuploader.UPLOAD_PROGRESS").apply {
            putExtra("current", current)
            putExtra("total", total)
            putExtra("fileName", fileName)
            putExtra("fileProgress", fileProgress)
        }
        sendBroadcast(intent)
    }

    private fun showCompletionNotification(total: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✅ Subida completada")
            .setContentText("$total archivos subidos exitosamente")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Broadcast de completado
        val intent = Intent("com.example.omvuploader.UPLOAD_PROGRESS").apply {
            putExtra("current", 0)
            putExtra("total", 0)
            putExtra("fileName", "")
        }
        sendBroadcast(intent)
    }

    private fun showErrorNotification(error: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("❌ Error en subida")
            .setContentText(error ?: "Error desconocido")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCancelledNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚫 Subida cancelada")
            .setContentText("La subida de archivos fue cancelada")
            .setSmallIcon(android.R.drawable.ic_delete)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)

        // Broadcast de cancelado
        val intent = Intent("com.example.omvuploader.UPLOAD_PROGRESS").apply {
            putExtra("current", 0)
            putExtra("total", 0)
            putExtra("fileName", "")
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}