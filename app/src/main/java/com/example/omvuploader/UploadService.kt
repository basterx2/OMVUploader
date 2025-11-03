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
        val uris = intent?.getParcelableArrayListExtra<Uri>("uris") ?: arrayListOf()
        val uploadPath = intent?.getStringExtra("uploadPath") ?: ""

        startForeground(NOTIFICATION_ID, createNotification(0, uris.size))

        serviceScope.launch {
            uploadFiles(uris, uploadPath)
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
                    val fileName = getFileName(uri)

                    // Simular progreso del archivo (0-100%)
                    for (progress in listOf(0, 25, 50, 75, 100)) {
                        updateNotification(index + 1, totalFiles, fileName, progress)
                        if (progress < 100) {
                            kotlinx.coroutines.delay(200) // Pequeña pausa para mostrar progreso
                        }
                    }

                    val inputStream = contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        smbManager.uploadFile(stream, fileName, uploadPath)
                    }

                    // Archivo completado al 100%
                    updateNotification(index + 1, totalFiles, fileName, 100)
                }

                showCompletionNotification(totalFiles)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(e.message)
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
                description = "Notificaciones de subida de archivos al servidor"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(current: Int, total: Int): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Subiendo archivos")
            .setContentText("$current de $total archivos")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(total, current, false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(current: Int, total: Int, fileName: String, fileProgress: Int = 0) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Subiendo archivos ($current/$total)")
            .setContentText("$fileName - $fileProgress%")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, fileProgress, false)
            .setOngoing(true)
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
            .setContentTitle("Subida completada")
            .setContentText("$total archivos subidos exitosamente")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
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
            .setContentTitle("Error en subida")
            .setContentText(error ?: "Error desconocido")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}