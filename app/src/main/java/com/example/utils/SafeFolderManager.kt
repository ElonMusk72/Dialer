package com.example.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.AudioPlayerActivity
import com.example.PhotoViewerActivity
import com.example.VideoPlayerActivity
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SafeFolderManager {

    private const val TAG = "SafeFolderManager"
    const val TYPE_VIDEO = "VIDEO"
    const val TYPE_PHOTO = "PHOTO"
    const val TYPE_DOCUMENT = "DOCUMENT"
    const val TYPE_AUDIO = "AUDIO"

    fun getSafeFolder(context: Context, fileType: String): File {
        val subDir = when (fileType) {
            TYPE_PHOTO -> "Photos"
            TYPE_VIDEO -> "Videos"
            TYPE_DOCUMENT -> "Documents"
            TYPE_AUDIO -> "Audio"
            else -> "Other"
        }
        val baseFolder = File(context.filesDir, "SafeFolder")
        if (!baseFolder.exists()) {
            baseFolder.mkdirs()
        }
        createNoMediaFile(baseFolder)

        val folder = File(baseFolder, subDir)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        createNoMediaFile(folder)

        return folder
    }

    private fun createNoMediaFile(folder: File) {
        try {
            val noMedia = File(folder, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create .nomedia file in $folder", e)
        }
    }

    /**
     * Hides a file by:
     * 1. Copying the file content to context.filesDir/SafeFolder/<subDir>
     * 2. Deleting the original file from external storage / origin
     * 3. Removing the entry from MediaStore so it disappears from gallery immediately
     */
    suspend fun hideFile(context: Context, sourceUri: Uri, fileType: String): VaultFileEntity? =
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val rawFileName = queryFileName(context, sourceUri) ?: "hidden_file_${System.currentTimeMillis()}"
                val mimeType = contentResolver.getType(sourceUri) ?: getMimeTypeFromExtension(rawFileName)

                val targetFolder = getSafeFolder(context, fileType)
                val extension = rawFileName.substringAfterLast('.', "")
                val baseName = rawFileName.substringBeforeLast('.')
                val safeFileName = if (extension.isNotEmpty()) {
                    "${baseName}_${System.currentTimeMillis()}.$extension"
                } else {
                    "${baseName}_${System.currentTimeMillis()}"
                }

                val destinationFile = File(targetFolder, safeFileName)
                var bytesCopied: Long = 0

                contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesCopied += read
                        }
                        output.flush()
                    }
                }

                val realSourcePath = queryRealPath(context, sourceUri)

                // Delete original file from original location
                deleteOriginalSourceFile(context, sourceUri, realSourcePath)

                val vaultFile = VaultFileEntity(
                    fileName = rawFileName,
                    originalPath = realSourcePath ?: sourceUri.toString(),
                    savedPath = destinationFile.absolutePath,
                    fileType = fileType,
                    fileSize = if (bytesCopied > 0) bytesCopied else destinationFile.length(),
                    mimeType = mimeType,
                    dateAdded = System.currentTimeMillis()
                )

                val insertedId = VaultDatabase.getDatabase(context).vaultFileDao().insert(vaultFile)
                vaultFile.copy(id = insertedId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide file from URI: $sourceUri", e)
                null
            }
        }

    /**
     * Deletes the original file and purges its record from MediaStore
     */
    private fun deleteOriginalSourceFile(context: Context, sourceUri: Uri, realSourcePath: String?) {
        val contentResolver = context.contentResolver

        // 1. Direct file deletion if real file path exists
        if (!realSourcePath.isNullOrEmpty()) {
            try {
                val originFile = File(realSourcePath)
                if (originFile.exists()) {
                    originFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting physical file at $realSourcePath", e)
            }
        }

        // 2. Try DocumentsContract deletion
        try {
            if (DocumentsContract.isDocumentUri(context, sourceUri)) {
                DocumentsContract.deleteDocument(contentResolver, sourceUri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DocumentsContract.deleteDocument failed for $sourceUri", e)
        }

        // 3. ContentResolver direct URI delete
        try {
            contentResolver.delete(sourceUri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver delete failed for $sourceUri", e)
        }

        // 4. Remove from MediaStore tables using real path or name
        if (!realSourcePath.isNullOrEmpty()) {
            try {
                contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DATA} = ?",
                    arrayOf(realSourcePath)
                )
                contentResolver.delete(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Video.Media.DATA} = ?",
                    arrayOf(realSourcePath)
                )
                contentResolver.delete(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(realSourcePath)
                )
                contentResolver.delete(
                    MediaStore.Files.getContentUri("external"),
                    "${MediaStore.Files.FileColumns.DATA} = ?",
                    arrayOf(realSourcePath)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error purging MediaStore rows for $realSourcePath", e)
            }

            // 5. Notify MediaScanner to refresh gallery index
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(realSourcePath),
                    null
                ) { _, _ ->
                    Log.d(TAG, "MediaScanner refreshed for $realSourcePath")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error triggering MediaScanner for $realSourcePath", e)
            }
        }
    }

    suspend fun deleteVaultFile(context: Context, vaultFile: VaultFileEntity): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val file = File(vaultFile.savedPath)
                if (file.exists()) {
                    file.delete()
                }
                VaultDatabase.getDatabase(context).vaultFileDao().delete(vaultFile)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting vault file", e)
                false
            }
        }

    suspend fun clearAllVaultFiles(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val safeFolder = File(context.filesDir, "SafeFolder")
                if (safeFolder.exists()) {
                    safeFolder.deleteRecursively()
                }
                VaultDatabase.getDatabase(context).vaultFileDao().deleteAll()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing vault", e)
                false
            }
        }

    /**
     * Opens the vault file INSIDE the app (In-App Player for Videos, In-App Viewer for Photos, etc.)
     */
    fun openVaultFile(context: Context, vaultFile: VaultFileEntity) {
        try {
            val file = File(vaultFile.savedPath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found in vault storage.", Toast.LENGTH_SHORT).show()
                return
            }

            when (vaultFile.fileType) {
                TYPE_VIDEO -> {
                    val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                        putExtra(VideoPlayerActivity.EXTRA_FILE_ID, vaultFile.id)
                        putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, vaultFile.savedPath)
                        putExtra(VideoPlayerActivity.EXTRA_FILE_NAME, vaultFile.fileName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                TYPE_PHOTO -> {
                    val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                        putExtra(PhotoViewerActivity.EXTRA_FILE_ID, vaultFile.id)
                        putExtra(PhotoViewerActivity.EXTRA_FILE_PATH, vaultFile.savedPath)
                        putExtra(PhotoViewerActivity.EXTRA_FILE_NAME, vaultFile.fileName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                TYPE_AUDIO -> {
                    val intent = Intent(context, AudioPlayerActivity::class.java).apply {
                        putExtra(AudioPlayerActivity.EXTRA_FILE_ID, vaultFile.id)
                        putExtra(AudioPlayerActivity.EXTRA_FILE_PATH, vaultFile.savedPath)
                        putExtra(AudioPlayerActivity.EXTRA_FILE_NAME, vaultFile.fileName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                else -> {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, vaultFile.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open file.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareVaultFile(context: Context, vaultFile: VaultFileEntity) {
        try {
            val file = File(vaultFile.savedPath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found.", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = vaultFile.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share ${vaultFile.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to share file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve file name from content resolver", e)
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun queryRealPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        if (uri.scheme == "content") {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val colIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val path = cursor.getString(colIndex)
                        if (!path.isNullOrEmpty()) return path
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query real path from uri: $uri", e)
            }
        }
        return uri.path
    }

    private fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        if (extension.isNotEmpty()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            if (!mime.isNullOrEmpty()) return mime
        }
        return "*/*"
    }

    fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val df = DecimalFormat("#,##0.#")
        return "${df.format(sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
