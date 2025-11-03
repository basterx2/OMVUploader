package com.example.omvuploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.example.omvuploader.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: UploadViewModel
    private lateinit var adapter: FilePreviewAdapter
    private val selectedFiles = mutableListOf<Uri>()

    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            selectedFiles.clear()
            selectedFiles.addAll(uris)
            adapter.submitList(selectedFiles.toList())
            binding.uploadButton.isEnabled = true
            val count = selectedFiles.size
            binding.selectedCount.text = "$count archivo${if (count > 1) "s" else ""} seleccionado${if (count > 1) "s" else ""}"
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Permisos necesarios denegados", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[UploadViewModel::class.java]

        setupRecyclerView()
        setupObservers()
        setupClickListeners()

        // Verificar permisos básicos
        checkBasicPermissions()

        // Verificar credenciales guardadas
        if (!viewModel.hasCredentials()) {
            showLoginDialog()
        } else {
            viewModel.testConnection()
        }
    }

    private fun checkBasicPermissions() {
        // Verificar permisos de red (estos no necesitan ser solicitados, solo declarados)
        val hasInternet = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED

        val hasNetworkState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasInternet || !hasNetworkState) {
            Toast.makeText(
                this,
                "La app necesita permisos de red. Reinstala la aplicación.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupRecyclerView() {
        adapter = FilePreviewAdapter { uri ->
            selectedFiles.remove(uri)
            adapter.submitList(selectedFiles.toList())
            binding.selectedCount.text = "Archivos seleccionados: ${selectedFiles.size}"
            binding.uploadButton.isEnabled = selectedFiles.isNotEmpty()
        }
        binding.filesRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.filesRecyclerView.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.uploadProgress.observe(this) { progress ->
            binding.progressBar.progress = progress
            binding.progressText.text = "$progress%"
        }

        viewModel.uploadStatus.observe(this) { status ->
            binding.statusText.text = status
        }

        viewModel.isUploading.observe(this) { isUploading ->
            binding.selectButton.isEnabled = !isUploading
            binding.uploadButton.isEnabled = !isUploading && selectedFiles.isNotEmpty()
            binding.progressCard.visibility = if (isUploading)
                android.view.View.VISIBLE else android.view.View.GONE
        }

        viewModel.connectionStatus.observe(this) { status ->
            binding.connectionStatus.text = status

            // Cambiar color del indicador
            val dotColor = if (status.contains("Conectado")) {
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            } else if (status.contains("Conectando")) {
                ContextCompat.getColor(this, android.R.color.holo_orange_dark)
            } else {
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            }
            binding.connectionDot.backgroundTintList = android.content.res.ColorStateList.valueOf(dotColor)
        }

        viewModel.uploadComplete.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "✅ Archivos subidos exitosamente", Toast.LENGTH_LONG).show()
                selectedFiles.clear()
                adapter.submitList(emptyList())
                binding.selectedCount.text = "No hay archivos seleccionados"
                binding.uploadButton.isEnabled = false
            }
        }
    }

    private fun setupClickListeners() {
        binding.selectButton.setOnClickListener {
            checkPermissionAndOpenPicker()
        }

        binding.uploadButton.setOnClickListener {
            if (selectedFiles.isNotEmpty()) {
                // Mostrar selector de carpeta
                val dialog = FolderSelectorDialog { selectedPath ->
                    // Usar la carpeta seleccionada o crear por fecha
                    val uploadPath = if (selectedPath.isEmpty()) {
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    } else {
                        "$selectedPath/${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}"
                    }

                    // Subir con ViewModel (muestra progreso en la app)
                    viewModel.uploadFiles(this, selectedFiles, uploadPath)

                    // También iniciar servicio para segundo plano
                    UploadService.startUpload(this, ArrayList(selectedFiles), uploadPath)
                }
                dialog.show(supportFragmentManager, "FolderSelector")
            }
        }

        binding.settingsButton.setOnClickListener {
            showLoginDialog()
        }

        binding.browseButton.setOnClickListener {
            val intent = Intent(this, FolderBrowserActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkPermissionAndOpenPicker() {
        when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+
                val permissions = arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
                if (permissions.all {
                        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                    }) {
                    openFilePicker()
                } else {
                    requestPermissionLauncher.launch(permissions)
                }
            }
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M -> {
                // Android 6.0 - 12
                val permissions = arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    openFilePicker()
                } else {
                    requestPermissionLauncher.launch(permissions)
                }
            }
            else -> {
                openFilePicker()
            }
        }
    }

    private fun openFilePicker() {
        pickMultipleMedia.launch("*/*")
    }

    private fun showLoginDialog() {
        val dialog = LoginDialogFragment.newInstance()
        dialog.setOnCredentialsSavedListener(object : LoginDialogFragment.OnCredentialsSavedListener {
            override fun onCredentialsSaved(server: String, username: String, password: String, shareName: String) {
                viewModel.saveCredentials(server, username, password, shareName)
                viewModel.testConnection()
            }
        })
        dialog.show(supportFragmentManager, "LoginDialog")
    }
}