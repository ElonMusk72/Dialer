package com.example.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
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
import com.example.utils.LogRecorder
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

    fun createNoMediaFile(folderPath: String) {
        try {
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val noMediaFile = File(folder, ".nomedia")
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not create .nomedia file in $folderPath", e)
        }
    }

    fun createNoMediaFile(folder: File) {
        createNoMediaFile(folder.absolutePath)
    }

    suspend fun hideFile(context: Context, sourceUri: Uri, fileType: String): VaultFileEntity? =
        withContext(Dispatchers.IO) {
            LogRecorder.logInfo(TAG, "Starting hideFile for Uri: $sourceUri (type=$fileType)")
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

                LogRecorder.logDebug(TAG, "Copying content from $sourceUri to ${destinationFile.absolutePath}")
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
                LogRecorder.logDebug(TAG, "Copied $bytesCopied bytes to ${destinationFile.absolutePath}")

                var realSourcePath: String? = queryRealPath(context, sourceUri)

                // FIX 1: Fallback to find physical path if queryRealPath returns null
                if (realSourcePath.isNullOrEmpty()) {
                    try {
                        var fileName = ""
                        var fileSize = 0L
                        contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: ""
                                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                            }
                        }

                        if (fileName.isNotEmpty() && fileSize > 0) {
                            val collectionUri = when {
                                mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                else -> MediaStore.Files.getContentUri("external")
                            }
                            
                            val projection = arrayOf(MediaStore.MediaColumns.DATA)
                            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
                            val selectionArgs = arrayOf(fileName, fileSize.toString())
                            
                            contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val path = cursor.getString(0) ?: ""
                                    if (path.isNotEmpty()) {
                                        realSourcePath = path
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogRecorder.logWarning(TAG, "Fallback MediaStore search failed: ${e.message}")
                    }
                }

                LogRecorder.logDebug(TAG, "Resolved real source path: $realSourcePath")

                var deletedSuccessfully = false

                // 1. Try DocumentsContract deletion
                try {
                    if (DocumentsContract.isDocumentUri(context, sourceUri)) {
                        DocumentsContract.deleteDocument(contentResolver, sourceUri)
                        deletedSuccessfully = true
                        LogRecorder.logDebug(TAG, "Deleted document via DocumentsContract: $sourceUri")
                    }
                } catch (e: Exception) {
                    LogRecorder.logWarning(TAG, "DocumentsContract.deleteDocument failed for $sourceUri: ${e.message}")
                }

                // 2. Delete direct ContentResolver URI
                if (!deletedSuccessfully) {
                    try {
                        val deleted = contentResolver.delete(sourceUri, null, null)
                        if (deleted > 0) {
                            deletedSuccessfully = true
                            LogRecorder.logDebug(TAG, "Deleted document via ContentResolver direct delete: $sourceUri")
                        }
                    } catch (e: Exception) {
                        LogRecorder.logWarning(TAG, "ContentResolver direct delete failed for $sourceUri: ${e.message}")
                    }
                }

                // 3. Delete original physical file (Works if MANAGE_EXTERNAL_STORAGE is granted)
                if (!deletedSuccessfully) {
                    realSourcePath?.let { path ->
                        try {
                            val originFile = File(path)
                            if (originFile.exists()) {
                                originFile.delete()
                                deletedSuccessfully = true
                                LogRecorder.logDebug(TAG, "Deleted physical original file at: $path")
                            }
                        } catch (e: Exception) {
                            LogRecorder.logWarning(TAG, "Error deleting physical file at $path: ${e.message}")
                        }
                    }
                }

                // 4. Remove the file's entry from MediaStore and Refresh Gallery Cache
                realSourcePath?.let { path ->
                    hideFileFromGallery(context, path)
                    refreshGallery(context, path)
                }

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
                LogRecorder.logSuccess(TAG, "File successfully hidden and saved to Room database with ID $insertedId: $rawFileName")
                return@withContext vaultFile.copy(id = insertedId)

            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Failed to hide file from URI: $sourceUri", e)
                return@withContext null
            }
        }

    fun hideFileFromGallery(context: Context, filePath: String): Boolean {
        return try {
            val contentResolver = context.contentResolver
            val uri = MediaStore.Files.getContentUri("external")
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(filePath)
            val deletedRows = contentResolver.delete(uri, selection, selectionArgs)

            try {
                contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
                contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
                contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
            } catch (_: Exception) {}

            deletedRows > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun refreshGallery(context: Context, filePath: String) {
        try {
            val file = File(filePath)
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "sendBroadcast for ACTION_MEDIA_SCANNER_SCAN_FILE failed", e)
        }

        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(filePath),
                null
            ) { path, uri ->
                Log.d(TAG, "MediaScanner refreshed for $path (uri=$uri)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaScannerConnection scanFile failed", e)
        }
    }

    fun removeFileFromMediaStore(context: Context, filePath: String): Boolean =
        hideFileFromGallery(context, filePath)

    suspend fun deleteVaultFile(context: Context, vaultFile: VaultFileEntity): Boolean =
        withContext(Dispatchers.IO) {
            LogRecorder.logInfo(TAG, "Deleting vault file: ${vaultFile.fileName} (id=${vaultFile.id})")
            try {
                val file = File(vaultFile.savedPath)
                if (file.exists()) {
                    file.delete()
                    LogRecorder.logDebug(TAG, "Deleted file on disk: ${vaultFile.savedPath}")
                }
                VaultDatabase.getDatabase(context).vaultFileDao().delete(vaultFile)
                LogRecorder.logSuccess(TAG, "Deleted entity from Room DB for fileId: ${vaultFile.id}")
                true
            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Error deleting vault file ${vaultFile.fileName}", e)
                false
            }
        }

    suspend fun clearAllVaultFiles(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            LogRecorder.logWarning(TAG, "Clearing all vault files")
            try {
                val safeFolder = File(context.filesDir, "SafeFolder")
                if (safeFolder.exists()) {
                    safeFolder.deleteRecursively()
                    LogRecorder.logDebug(TAG, "Deleted SafeFolder directory recursively")
                }
                VaultDatabase.getDatabase(context).vaultFileDao().deleteAll()
                LogRecorder.logSuccess(TAG, "Cleared all vault records from Room database")
                true
            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Error clearing vault", e)
                false
            }
        }

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

    fun queryRealPath(context: Context, uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val authority = uri.authority

            if ("com.android.externalstorage.documents" == authority) {
                val split = docId.split(":")
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/${if (split.size > 1) split[1] else ""}"
                }
            } else if ("com.android.providers.media.documents" == authority) {
                val split = docId.split(":")
                val type = split[0]
                val id = if (split.size > 1) split[1] else return null
           val contentUri = when (type) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(id)
                return getDataColumn(context, contentUri, selection, selectionArgs)
            } else if ("com.android.providers.downloads.documents" == authority) {
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"),
                    docId.toLongOrNull() ?: return null
                )
                return getDataColumn(context, contentUri, null, null)
            }
        }

        if ("content".equals(uri.scheme, ignoreCase = true)) {
            val path = getDataColumn(context, uri, null, null)
            if (!path.isNullOrEmpty()) return path
        }

        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        return uri.path
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        val column = MediaStore.MediaColumns.DATA
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    return cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getDataColumn failed for $uri", e)
        }
        return null
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
