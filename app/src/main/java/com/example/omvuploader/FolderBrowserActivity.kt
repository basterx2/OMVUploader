package com.example.omvuploader

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.omvuploader.databinding.ActivityFolderBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFolderBrowserBinding
    private lateinit var adapter: MediaGridAdapter
    private var smbManager: SMBManager? = null
    private var currentPath = ""
    private val pathHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Explorador de Medios"
        supportActionBar?.elevation = 0f

        setupRecyclerView()
        initializeSMB()
        loadFiles()
    }

    private fun setupRecyclerView() {
        adapter = MediaGridAdapter(
            onItemClick = { fileItem ->
                if (fileItem.isDirectory) {
                    navigateToFolder(fileItem.name)
                } else {
                    openMediaViewer(fileItem)
                }
            },
            onDownloadClick = { fileItem ->
                Toast.makeText(this, "Descargando: ${fileItem.name}", Toast.LENGTH_SHORT).show()
            }
        )

        val spanCount = 3
        val gridLayoutManager = GridLayoutManager(this, spanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position < adapter.currentList.size && adapter.currentList[position].isDirectory) {
                    spanCount
                } else {
                    1
                }
            }
        }

        binding.mediaRecyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = this@FolderBrowserActivity.adapter
            setHasFixedSize(true)
            // Desactivar animaciones para mejor rendimiento
            itemAnimator = null
        }
    }

    private fun initializeSMB() {
        val credentialsManager = CredentialsManager(this)
        val credentials = credentialsManager.getCredentials()

        if (credentials != null) {
            lifecycleScope.launch {
                try {
                    smbManager = SMBManager(
                        credentials.server,
                        credentials.username,
                        credentials.password
                    )
                    withContext(Dispatchers.IO) {
                        smbManager?.initialize()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@FolderBrowserActivity,
                        "Error al inicializar: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Toast.makeText(this, "No hay credenciales guardadas", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadFiles() {
        if (smbManager == null) {
            Toast.makeText(this, "SMB no inicializado", Toast.LENGTH_SHORT).show()
            return
        }

        val credentialsManager = CredentialsManager(this)
        val shareName = credentialsManager.getCredentials()?.shareName ?: "RaspberryHDD"

        println("FolderBrowser: Iniciando carga de archivos para: $currentPath")
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.currentPath.text = if (currentPath.isEmpty()) "📁 $shareName" else "📁 $shareName/$currentPath"

        lifecycleScope.launch {
            try {
                println("FolderBrowser: Llamando a listFiles en IO thread")
                val files = withContext(Dispatchers.IO) {
                    smbManager?.listFiles(currentPath) ?: emptyList()
                }

                println("FolderBrowser: Recibidos ${files.size} archivos")
                files.forEachIndexed { index, file ->
                    println("FolderBrowser: [$index] ${file.name} (${if(file.isDirectory) "DIR" else "FILE"})")
                }

                withContext(Dispatchers.Main) {
                    println("FolderBrowser: Actualizando UI en Main thread")

                    if (files.isEmpty()) {
                        binding.emptyState.visibility = View.VISIBLE
                        binding.emptyStateText.text = "No hay archivos en esta carpeta"
                        adapter.submitList(emptyList())
                    } else {
                        binding.emptyState.visibility = View.GONE
                        println("FolderBrowser: Llamando a adapter.submitList con ${files.size} items")
                        adapter.submitList(files) {
                            println("FolderBrowser: submitList completado")
                        }
                    }

                    binding.progressBar.visibility = View.GONE
                    println("FolderBrowser: Carga completada")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("FolderBrowser: Error - ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@FolderBrowserActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.emptyState.visibility = View.VISIBLE
                    binding.emptyStateText.text = "Error al cargar archivos"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun navigateToFolder(folderName: String) {
        pathHistory.add(currentPath)
        currentPath = if (currentPath.isEmpty()) {
            folderName
        } else {
            "$currentPath/$folderName"
        }
        loadFiles()
    }

    private fun openMediaViewer(fileItem: FileItem) {
        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putExtra("FILE_NAME", fileItem.name)
            putExtra("FILE_PATH", fileItem.path)
            putExtra("IS_VIDEO", fileItem.name.lowercase().let {
                it.endsWith(".mp4") || it.endsWith(".avi") ||
                        it.endsWith(".mkv") || it.endsWith(".mov")
            })
        }
        startActivity(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (pathHistory.isNotEmpty()) {
            currentPath = pathHistory.removeAt(pathHistory.size - 1)
            loadFiles()
        } else {
            super.onBackPressed()
        }
    }
}