package com.example.adapters

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.VaultFileEntity
import com.example.databinding.ItemVaultFileBinding
import com.example.utils.LogRecorder
import com.example.utils.SafeFolderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VaultFileAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (VaultFileEntity) -> Unit,
    private val onShareClick: (VaultFileEntity) -> Unit,
    private val onDeleteClick: (VaultFileEntity) -> Unit
) : ListAdapter<VaultFileEntity, VaultFileAdapter.VaultFileViewHolder>(DiffCallback) {

    // Simple in-memory bitmap cache for thumbnails
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VaultFileViewHolder {
        val binding = ItemVaultFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VaultFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VaultFileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VaultFileViewHolder(private val binding: ItemVaultFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun bind(file: VaultFileEntity) {
            loadJob?.cancel()

            binding.tvFileName.text = file.fileName
            binding.tvFileSize.text = SafeFolderManager.formatFileSize(file.fileSize)
            binding.tvFileDate.text = SafeFolderManager.formatDate(file.dateAdded)

            // Reset view state
            binding.ivThumbnail.visibility = View.GONE
            binding.tvTypeIcon.visibility = View.VISIBLE
            binding.ivVideoBadge.visibility = View.GONE

            when (file.fileType) {
                SafeFolderManager.TYPE_PHOTO -> {
                    binding.tvTypeIcon.text = "📷"
                    loadThumbnail(file.savedPath, isVideo = false)
                }
                SafeFolderManager.TYPE_VIDEO -> {
                    binding.tvTypeIcon.text = "🎬"
                    binding.ivVideoBadge.visibility = View.VISIBLE
                    loadThumbnail(file.savedPath, isVideo = true)
                }
                SafeFolderManager.TYPE_DOCUMENT -> {
                    binding.tvTypeIcon.text = getDocEmoji(file.fileName)
                }
                SafeFolderManager.TYPE_AUDIO -> {
                    binding.tvTypeIcon.text = "🎵"
                }
                else -> {
                    binding.tvTypeIcon.text = "📁"
                }
            }

            binding.root.setOnClickListener {
                LogRecorder.logInfo(TAG, "Item clicked: ${file.fileName} (id=${file.id}, type=${file.fileType})")
                onItemClick(file)
            }

            binding.root.setOnLongClickListener {
                LogRecorder.logInfo(TAG, "Item long-clicked: ${file.fileName} (id=${file.id})")
                onDeleteClick(file)
                true
            }

            binding.btnMoreOptions.setOnClickListener { view ->
                LogRecorder.logDebug(TAG, "More options clicked for item: ${file.fileName}")
                showPopupMenu(view, file)
            }
        }

        private fun loadThumbnail(filePath: String, isVideo: Boolean) {
            val cached = memoryCache.get(filePath)
            if (cached != null) {
                binding.ivThumbnail.setImageBitmap(cached)
                binding.ivThumbnail.visibility = View.VISIBLE
                binding.tvTypeIcon.visibility = View.GONE
                return
            }

            loadJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val file = File(filePath)
                        if (!file.exists()) return@withContext null

                        if (isVideo) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                ThumbnailUtils.createVideoThumbnail(file, Size(120, 120), null)
                            } else {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(filePath)
                                    retriever.getFrameAtTime(1000000)
                                } finally {
                                    retriever.release()
                                }
                            }
                        } else {
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeFile(filePath, options)
                            var sampleSize = 1
                            val targetDim = 120
                            while ((options.outWidth / sampleSize) > targetDim || (options.outHeight / sampleSize) > targetDim) {
                                sampleSize *= 2
                            }
                            val decodeOptions = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                            }
                            BitmapFactory.decodeFile(filePath, decodeOptions)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmap != null) {
                    memoryCache.put(filePath, bitmap)
                    binding.ivThumbnail.setImageBitmap(bitmap)
                    binding.ivThumbnail.visibility = View.VISIBLE
                    binding.tvTypeIcon.visibility = View.GONE
                }
            }
        }

        private fun showPopupMenu(view: View, file: VaultFileEntity) {
            val popup = PopupMenu(view.context, view)
            popup.menu.add(0, 1, 0, "Open / View")
            popup.menu.add(0, 2, 1, "Share / Export")
            popup.menu.add(0, 3, 2, "Delete from Vault")

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onItemClick(file)
                    2 -> onShareClick(file)
                    3 -> onDeleteClick(file)
                }
                true
            }
            popup.show()
        }

        private fun getDocEmoji(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".pdf") -> "📕"
                lower.endsWith(".doc") || lower.endsWith(".docx") -> "📝"
                lower.endsWith(".txt") -> "📄"
                lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📊"
                lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📑"
                lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "📦"
                else -> "📄"
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<VaultFileEntity>() {
        private const val TAG = "VaultFileAdapter"

        override fun areItemsTheSame(oldItem: VaultFileEntity, newItem: VaultFileEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: VaultFileEntity, newItem: VaultFileEntity): Boolean {
            return oldItem == newItem
        }
    }
}
