package com.example.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.VaultDatabase
import com.example.data.VaultFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
        val folder = File(context.filesDir, "SafeFolder/$subDir")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    suspend fun hideFile(context: Context, sourceUri: Uri, fileType: String): VaultFileEntity? =
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val rawFileName = queryFileName(context, sourceUri) ?: "hidden_file_${System.currentTimeMillis()}"
                val mimeType = contentResolver.getType(sourceUri) ?: getMimeTypeFromExtension(rawFileName)

                val targetFolder = getSafeFolder(context, fileType)
                // Use timestamped unique name to prevent collisions while preserving extension
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

                val vaultFile = VaultFileEntity(
                    fileName = rawFileName,
                    originalPath = sourceUri.toString(),
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

    fun openVaultFile(context: Context, vaultFile: VaultFileEntity) {
        try {
            val file = File(vaultFile.savedPath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found in storage.", Toast.LENGTH_SHORT).show()
                return
            }
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
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open this file.", Toast.LENGTH_SHORT).show()
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
