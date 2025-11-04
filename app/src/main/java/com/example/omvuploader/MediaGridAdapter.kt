package com.example.omvuploader

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.omvuploader.databinding.ItemMediaGridBinding
import com.example.omvuploader.databinding.ItemFolderGridBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaGridAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onDownloadClick: (FileItem) -> Unit
) : ListAdapter<FileItem, RecyclerView.ViewHolder>(FileItemDiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_MEDIA = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isDirectory) TYPE_FOLDER else TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> {
                val binding = ItemFolderGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                FolderViewHolder(binding)
            }
            else -> {
                val binding = ItemMediaGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                MediaViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        println("MediaGridAdapter: Binding position $position - ${item.name}")
        when (holder) {
            is FolderViewHolder -> holder.bind(item)
            is MediaViewHolder -> holder.bind(item)
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(fileItem: FileItem) {
            binding.folderName.text = fileItem.name

            val fileCount = 0 // Puedes implementar conteo si lo necesitas
            binding.folderInfo.text = if (fileCount > 0) "$fileCount archivos" else "Carpeta"

            binding.root.setOnClickListener {
                onItemClick(fileItem)
            }
        }
    }

    inner class MediaViewHolder(
        private val binding: ItemMediaGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun bind(fileItem: FileItem) {
            // Cancelar carga anterior si existe
            loadJob?.cancel()

            // Mostrar nombre del archivo
            binding.fileName.text = fileItem.name

            // Determinar tipo de archivo
            val isVideo = fileItem.name.lowercase().let {
                it.endsWith(".mp4") || it.endsWith(".avi") ||
                        it.endsWith(".mkv") || it.endsWith(".mov") ||
                        it.endsWith(".3gp") || it.endsWith(".webm")
            }

            val isImage = fileItem.name.lowercase().let {
                it.endsWith(".jpg") || it.endsWith(".jpeg") ||
                        it.endsWith(".png") || it.endsWith(".gif") ||
                        it.endsWith(".bmp") || it.endsWith(".webp")
            }

            // Mostrar indicador de tipo
            if (isVideo) {
                binding.videoIndicator.visibility = View.VISIBLE
            } else {
                binding.videoIndicator.visibility = View.GONE
            }

            // Cargar miniatura
            if (isImage || isVideo) {
                loadThumbnail(fileItem)
            } else {
                binding.thumbnail.setImageResource(R.drawable.ic_file_placeholder)
            }

            // Click para ver en pantalla completa
            binding.root.setOnClickListener {
                onItemClick(fileItem)
            }

            // Click largo para descargar
            binding.root.setOnLongClickListener {
                onDownloadClick(fileItem)
                true
            }
        }

        private fun loadThumbnail(fileItem: FileItem) {
            // Cancelar carga anterior
            loadJob?.cancel()

            // Mostrar placeholder
            binding.thumbnail.setImageResource(R.drawable.ic_file_placeholder)

            loadJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val context = binding.root.context
                    val credentialsManager = CredentialsManager(context)
                    val credentials = credentialsManager.getCredentials() ?: return@launch

                    // Crear cache directory
                    val cacheDir = File(context.cacheDir, "thumbnails")
                    if (!cacheDir.exists()) cacheDir.mkdirs()

                    // Generar nombre único para cache
                    val cacheFileName = fileItem.path.hashCode().toString() + ".jpg"
                    val cacheFile = File(cacheDir, cacheFileName)

                    // Si existe en cache, cargar desde ahí
                    if (cacheFile.exists()) {
                        withContext(Dispatchers.Main) {
                            Glide.with(context)
                                .load(cacheFile)
                                .apply(RequestOptions()
                                    .override(200, 200)
                                    .centerCrop())
                                .into(binding.thumbnail)
                        }
                        return@launch
                    }

                    // Si no existe, descargar y guardar en cache
                    val smbManager = SMBManager(
                        credentials.server,
                        credentials.username,
                        credentials.password,
                        credentials.shareName ?: "RaspberryHDD"
                    )
                    smbManager.initialize()

                    // Descargar thumbnail (archivos completos pequeños o 500KB de grandes)
                    val thumbnailData = smbManager.downloadFileThumbnail(fileItem.path)

                    if (thumbnailData != null && thumbnailData.isNotEmpty()) {
                        // Guardar en cache
                        cacheFile.writeBytes(thumbnailData)

                        // Cargar con Glide desde cache
                        withContext(Dispatchers.Main) {
                            Glide.with(context)
                                .load(cacheFile)
                                .apply(RequestOptions()
                                    .override(200, 200)
                                    .centerCrop()
                                    .diskCacheStrategy(DiskCacheStrategy.ALL))
                                .placeholder(R.drawable.ic_file_placeholder)
                                .error(android.R.drawable.ic_menu_gallery)
                                .into(binding.thumbnail)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("MediaGridAdapter: Error cargando thumbnail: ${e.message}")
                    withContext(Dispatchers.Main) {
                        binding.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                    }
                }
            }
        }
    }

    private class FileItemDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem == newItem
        }
    }
}