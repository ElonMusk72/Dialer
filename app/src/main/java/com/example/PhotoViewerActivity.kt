package com.example

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import com.example.databinding.ActivityPhotoViewerBinding
import com.example.utils.LogRecorder
import com.example.utils.SafeFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private var vaultFileId: Long = -1L
    private var vaultFile: VaultFileEntity? = null
    private var isTopBarVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vaultFileId = intent.getLongExtra(EXTRA_FILE_ID, -1L)
        val directPath = intent.getStringExtra(EXTRA_FILE_PATH)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)

        LogRecorder.logInfo(TAG, "PhotoViewerActivity created: fileId=$vaultFileId, fileName=$fileName, path=$directPath")

        binding.tvPhotoTitle.text = fileName ?: "Photo Viewer"

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.zoomImageView.setOnClickListener {
            toggleControls()
        }

        binding.btnShare.setOnClickListener {
            vaultFile?.let { SafeFolderManager.shareVaultFile(this, it) }
        }

        binding.btnDelete.setOnClickListener {
            confirmDelete()
        }

        loadPhoto(vaultFileId, directPath)
    }

    private fun toggleControls() {
        isTopBarVisible = !isTopBarVisible
        binding.topBar.visibility = if (isTopBarVisible) View.VISIBLE else View.GONE
    }

    private fun loadPhoto(fileId: Long, directPath: String?) {
        lifecycleScope.launch {
            val fileEntity = withContext(Dispatchers.IO) {
                if (fileId != -1L) {
                    VaultDatabase.getDatabase(this@PhotoViewerActivity).vaultFileDao().getAllFilesSync()
                        .firstOrNull { it.id == fileId }
                } else null
            }
            vaultFile = fileEntity

            val path = fileEntity?.savedPath ?: directPath
            if (path.isNullOrEmpty()) {
                LogRecorder.logError(TAG, "Photo file path is null or empty for fileId=$fileId")
                Toast.makeText(this@PhotoViewerActivity, "Photo not found.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            binding.tvPhotoTitle.text = fileEntity?.fileName ?: File(path).name

            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                LogRecorder.logSuccess(TAG, "Photo loaded successfully: $path")
                binding.zoomImageView.setImageBitmap(bitmap)
            } else {
                LogRecorder.logError(TAG, "Failed to decode photo bitmap from path: $path")
                Toast.makeText(this@PhotoViewerActivity, "Failed to load image.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        val file = vaultFile ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Photo")
            .setMessage("Are you sure you want to permanently delete \"${file.fileName}\" from your vault?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = SafeFolderManager.deleteVaultFile(this@PhotoViewerActivity, file)
                    if (deleted) {
                        Toast.makeText(this@PhotoViewerActivity, "Photo deleted.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "PhotoViewerActivity"
        const val EXTRA_FILE_ID = "extra_vault_file_id"
        const val EXTRA_FILE_PATH = "extra_vault_file_path"
        const val EXTRA_FILE_NAME = "extra_vault_file_name"
    }
}
