package com.example.omvuploader

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.omvuploader.databinding.ActivityMediaViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private var filePath: String? = null
    private var isVideo: Boolean = false
    private var tempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtener datos del intent
        val fileName = intent.getStringExtra("FILE_NAME") ?: "Archivo"
        filePath = intent.getStringExtra("FILE_PATH")
        isVideo = intent.getBooleanExtra("IS_VIDEO", false)

        supportActionBar?.title = fileName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupUI()
        loadMedia()
    }

    private fun setupUI() {
        if (isVideo) {
            binding.imageView.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE

            // Configurar controles de video
            val mediaController = MediaController(this)
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mediaController)

            // Mantener proporción original del video
            binding.videoView.setOnPreparedListener { mp ->
                // Ajustar el tamaño del VideoView para mantener la proporción
                val videoWidth = mp.videoWidth
                val videoHeight = mp.videoHeight
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels

                val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
                val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

                val layoutParams = binding.videoView.layoutParams
                if (videoRatio > screenRatio) {
                    // Video es más ancho
                    layoutParams.width = screenWidth
                    layoutParams.height = (screenWidth / videoRatio).toInt()
                } else {
                    // Video es más alto
                    layoutParams.height = screenHeight
                    layoutParams.width = (screenHeight * videoRatio).toInt()
                }
                binding.videoView.layoutParams = layoutParams
            }
        } else {
            binding.imageView.visibility = View.VISIBLE
            binding.videoView.visibility = View.GONE
        }

        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    private fun loadMedia() {
        binding.progressBar.visibility = View.VISIBLE
        binding.loadingText.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val credentialsManager = CredentialsManager(this@MediaViewerActivity)
                val credentials = credentialsManager.getCredentials()

                if (credentials != null && filePath != null) {
                    val smbManager = SMBManager(
                        credentials.server,
                        credentials.username,
                        credentials.password,
                        credentials.shareName ?: "RaspberryHDD"
                    )

                    withContext(Dispatchers.IO) {
                        smbManager.initialize()
                    }

                    if (isVideo) {
                        loadVideo(smbManager)
                    } else {
                        loadImage(smbManager)
                    }
                } else {
                    showError("No se pudo cargar el archivo")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Error: ${e.message}")
            }
        }
    }

    private suspend fun loadImage(smbManager: SMBManager) {
        try {
            val imageData = withContext(Dispatchers.IO) {
                smbManager.downloadFile(filePath!!)
            }

            if (imageData != null) {
                val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        binding.imageView.setImageBitmap(bitmap)
                        binding.progressBar.visibility = View.GONE
                        binding.loadingText.visibility = View.GONE
                    }
                } else {
                    showError("No se pudo decodificar la imagen")
                }
            } else {
                showError("No se pudo descargar la imagen")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showError("Error al cargar imagen: ${e.message}")
        }
    }

    private suspend fun loadVideo(smbManager: SMBManager) {
        try {
            binding.loadingText.text = "Descargando video..."

            val videoData = withContext(Dispatchers.IO) {
                smbManager.downloadFile(filePath!!)
            }

            if (videoData != null) {
                withContext(Dispatchers.Main) {
                    binding.loadingText.text = "Preparando reproducción..."
                }

                // Guardar en archivo temporal
                tempFile = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("video", ".mp4", cacheDir)
                    FileOutputStream(file).use { output ->
                        output.write(videoData)
                    }
                    file
                }

                withContext(Dispatchers.Main) {
                    binding.videoView.setVideoURI(Uri.fromFile(tempFile))
                    binding.videoView.setOnPreparedListener { mp ->
                        binding.progressBar.visibility = View.GONE
                        binding.loadingText.visibility = View.GONE
                        mp.start()
                    }
                    binding.videoView.setOnErrorListener { _, what, extra ->
                        showError("Error al reproducir video: $what, $extra")
                        true
                    }
                }
            } else {
                showError("No se pudo descargar el video")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showError("Error al cargar video: ${e.message}")
        }
    }

    private fun showError(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.visibility = View.GONE
            binding.loadingText.visibility = View.GONE
            Toast.makeText(this@MediaViewerActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpiar archivo temporal
        tempFile?.delete()
        binding.videoView.stopPlayback()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}