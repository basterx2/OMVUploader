package com.example.omvuploader

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.omvuploader.databinding.ActivityMediaViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var adapter: MediaPagerAdapter
    private var filesList = mutableListOf<FileItem>()
    private var currentPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ocultar action bar
        supportActionBar?.hide()

        val fileName = intent.getStringExtra("FILE_NAME") ?: "Archivo"
        val filePath = intent.getStringExtra("FILE_PATH")
        val currentPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        binding.fileName.text = fileName
        binding.closeButton.setOnClickListener { finish() }

        loadAllFiles(filePath, currentPath)
    }

    private fun loadAllFiles(selectedFilePath: String?, currentPath: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val credentialsManager = CredentialsManager(this@MediaViewerActivity)
                val credentials = credentialsManager.getCredentials()

                if (credentials != null) {
                    val smbManager = SMBManager(
                        credentials.server,
                        credentials.username,
                        credentials.password,
                        credentials.shareName ?: "RaspberryHDD"
                    )

                    withContext(Dispatchers.IO) {
                        smbManager.initialize()
                    }

                    // Obtener todos los archivos de la carpeta actual
                    val allFiles = withContext(Dispatchers.IO) {
                        smbManager.listFiles(currentPath)
                    }

                    // Filtrar solo imágenes y videos
                    filesList = allFiles.filter { !it.isDirectory && isMediaFile(it.name) }.toMutableList()

                    // Encontrar posición del archivo seleccionado
                    currentPosition = filesList.indexOfFirst { it.path == selectedFilePath }.coerceAtLeast(0)

                    // Configurar adapter
                    adapter = MediaPagerAdapter(this@MediaViewerActivity, filesList, smbManager)
                    binding.viewPager.adapter = adapter
                    binding.viewPager.setCurrentItem(currentPosition, false)

                    // Actualizar contador
                    updateCounter(currentPosition)

                    // Listener para cambio de página
                    binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            currentPosition = position
                            updateCounter(position)
                            binding.fileName.text = filesList[position].name
                        }
                    })

                    binding.progressBar.visibility = View.GONE
                    binding.loadingText.visibility = View.GONE
                } else {
                    showError("No se pudo cargar el archivo")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showError("Error: ${e.message}")
            }
        }
    }

    private fun updateCounter(position: Int) {
        binding.counter.text = "${position + 1}/${filesList.size}"
    }

    private fun isMediaFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".bmp") || lower.endsWith(".webp") ||
                lower.endsWith(".mp4") || lower.endsWith(".avi") ||
                lower.endsWith(".mkv") || lower.endsWith(".mov") ||
                lower.endsWith(".3gp") || lower.endsWith(".webm")
    }

    private fun showError(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.visibility = View.GONE
            binding.loadingText.visibility = View.GONE
            Toast.makeText(this@MediaViewerActivity, message, Toast.LENGTH_LONG).show()
        }
    }
}