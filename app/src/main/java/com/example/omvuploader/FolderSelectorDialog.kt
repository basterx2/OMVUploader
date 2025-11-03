package com.example.omvuploader

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.omvuploader.databinding.DialogFolderSelectorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderSelectorDialog(
    private val onFolderSelected: (String) -> Unit
) : DialogFragment() {

    private var _binding: DialogFolderSelectorBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FolderSelectorAdapter
    private var smbManager: SMBManager? = null
    private var currentPath = ""
    private val pathHistory = mutableListOf<String>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFolderSelectorBinding.inflate(LayoutInflater.from(context))

        setupRecyclerView()
        initializeSMB()

        return AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar Carpeta")
            .setView(binding.root)
            .setPositiveButton("Seleccionar Aquí") { _, _ ->
                onFolderSelected(currentPath)
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Atrás") { _, _ ->
                goBack()
            }
            .create().also { dialog ->
                // Agregar listener para el botón de crear carpeta
                dialog.setOnShowListener {
                    binding.createFolderButton.setOnClickListener {
                        showCreateFolderDialog()
                    }
                }
            }
    }

    private fun setupRecyclerView() {
        adapter = FolderSelectorAdapter { folderName ->
            navigateToFolder(folderName)
        }
        binding.foldersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.foldersRecyclerView.adapter = adapter
    }

    private fun initializeSMB() {
        val credentialsManager = CredentialsManager(requireContext())
        val credentials = credentialsManager.getCredentials()

        if (credentials != null) {
            binding.progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    smbManager = withContext(Dispatchers.IO) {
                        val manager = SMBManager(
                            credentials.server,
                            credentials.username,
                            credentials.password,
                            credentials.shareName ?: "RaspberryHDD"
                        )
                        manager.initialize()
                        manager
                    }
                    loadFolders()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        } else {
            Toast.makeText(requireContext(), "No hay credenciales", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun loadFolders() {
        binding.progressBar.visibility = View.VISIBLE
        binding.currentPathText.text = "📁 ${if (currentPath.isEmpty()) "Fotos" else "Fotos/$currentPath"}"

        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    smbManager?.listFiles(currentPath) ?: emptyList()
                }

                // Filtrar solo carpetas
                val folders = files.filter { it.isDirectory }
                adapter.submitList(folders)

                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
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
        loadFolders()
    }

    private fun goBack() {
        if (pathHistory.isNotEmpty()) {
            currentPath = pathHistory.removeAt(pathHistory.size - 1)
            loadFolders()
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Nombre de la carpeta"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Crear Nueva Carpeta")
            .setMessage("La carpeta se creará en: ${if (currentPath.isEmpty()) "Fotos" else "Fotos/$currentPath"}")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    if (folderName.contains("/") || folderName.contains("\\")) {
                        Toast.makeText(requireContext(), "El nombre no puede contener / o \\", Toast.LENGTH_SHORT).show()
                    } else {
                        createFolder(folderName)
                    }
                } else {
                    Toast.makeText(requireContext(), "Ingresa un nombre válido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createFolder(folderName: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    smbManager?.createFolder(currentPath, folderName) ?: false
                }

                if (success) {
                    Toast.makeText(requireContext(), "✅ Carpeta '$folderName' creada", Toast.LENGTH_SHORT).show()
                    loadFolders() // Recargar lista
                } else {
                    Toast.makeText(requireContext(), "❌ Error: La carpeta ya existe o no se pudo crear", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}