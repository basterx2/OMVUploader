package com.example.omvuploader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.recyclerview.widget.RecyclerView
import com.example.omvuploader.databinding.ItemMediaPageBinding
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MediaPagerAdapter(
    private val context: Context,
    private val items: List<FileItem>,
    private val smbManager: SMBManager
) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class MediaViewHolder(
        private val binding: ItemMediaPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(fileItem: FileItem) {
            val isVideo = fileItem.name.lowercase().let {
                it.endsWith(".mp4") || it.endsWith(".avi") ||
                        it.endsWith(".mkv") || it.endsWith(".mov") ||
                        it.endsWith(".3gp") || it.endsWith(".webm")
            }

            if (isVideo) {
                loadVideo(fileItem)
            } else {
                loadImage(fileItem)
            }
        }

        private fun loadImage(fileItem: FileItem) {
            binding.photoView.visibility = View.VISIBLE
            binding.videoView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imageData = smbManager.downloadFile(fileItem.path)

                    if (imageData != null) {
                        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)

                        withContext(Dispatchers.Main) {
                            if (bitmap != null) {
                                binding.photoView.setImageBitmap(bitmap)
                                binding.progressBar.visibility = View.GONE
                            } else {
                                binding.errorText.visibility = View.VISIBLE
                                binding.errorText.text = "Error al decodificar imagen"
                                binding.progressBar.visibility = View.GONE
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.errorText.visibility = View.VISIBLE
                            binding.errorText.text = "Error al descargar imagen"
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = "Error: ${e.message}"
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }

        private fun loadVideo(fileItem: FileItem) {
            binding.photoView.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.progressBar.visibility = View.VISIBLE

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val videoData = smbManager.downloadFile(fileItem.path)

                    if (videoData != null) {
                        val tempFile = File.createTempFile("video", ".mp4", context.cacheDir)
                        FileOutputStream(tempFile).use { output ->
                            output.write(videoData)
                        }

                        withContext(Dispatchers.Main) {
                            val mediaController = MediaController(context)
                            mediaController.setAnchorView(binding.videoView)
                            binding.videoView.setMediaController(mediaController)
                            binding.videoView.setVideoURI(Uri.fromFile(tempFile))

                            binding.videoView.setOnPreparedListener { mp ->
                                binding.progressBar.visibility = View.GONE

                                // Ajustar tamaño manteniendo proporción
                                val videoWidth = mp.videoWidth
                                val videoHeight = mp.videoHeight
                                val screenWidth = context.resources.displayMetrics.widthPixels
                                val screenHeight = context.resources.displayMetrics.heightPixels

                                val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
                                val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

                                val layoutParams = binding.videoView.layoutParams
                                if (videoRatio > screenRatio) {
                                    layoutParams.width = screenWidth
                                    layoutParams.height = (screenWidth / videoRatio).toInt()
                                } else {
                                    layoutParams.height = screenHeight
                                    layoutParams.width = (screenHeight * videoRatio).toInt()
                                }
                                binding.videoView.layoutParams = layoutParams

                                mp.start()
                            }

                            binding.videoView.setOnErrorListener { _, what, extra ->
                                binding.errorText.visibility = View.VISIBLE
                                binding.errorText.text = "Error al reproducir video"
                                binding.progressBar.visibility = View.GONE
                                true
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.errorText.visibility = View.VISIBLE
                            binding.errorText.text = "Error al descargar video"
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = "Error: ${e.message}"
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }
}