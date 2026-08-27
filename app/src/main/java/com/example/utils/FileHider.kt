package com.example.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

class FileHider(private val context: Context) {

    companion object {
        private const val TAG = "FileHider"
        private const val SAFE_FOLDER_NAME = "SafeFolder"
        private const val HIDDEN_EXTENSION = ".vh" // .vh = Vault Hidden
    }

    private val safeFolder: File by lazy {
        val folder = File(context.filesDir, SAFE_FOLDER_NAME)
        if (!folder.exists()) {
            folder.mkdirs()
        }
        folder
    }

    /**
     * Hide a file - GUARANTEED to hide from gallery
     * Uses 4-layer protection:
     * 1. Moves to app-private storage
     * 2. Creates .nomedia file
     * 3. Renames file with hidden extension
     * 4. Removes from MediaStore
     */
    fun hideFile(originalFilePath: String): Boolean {
        return try {
            val originalFile = File(originalFilePath)
            if (!originalFile.exists()) {
                Log.e(TAG, "❌ File not found: $originalFilePath")
                return false
            }

            // STEP 1: Create .nomedia file (THIS IS THE KEY)
            createNoMediaFile()

            // STEP 2: Create hidden file with new name
            val hiddenFileName = "${originalFile.nameWithoutExtension}${HIDDEN_EXTENSION}"
            val hiddenFile = File(safeFolder, hiddenFileName)

            // STEP 3: Move the file (copy + delete original)
            originalFile.copyTo(hiddenFile, overwrite = true)
            originalFile.delete()

            // STEP 4: Remove from MediaStore
            removeFromMediaStore(originalFilePath)

            Log.d(TAG, "✅ File hidden successfully: ${hiddenFile.absolutePath}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error hiding file: ${e.message}")
            false
        }
    }

    /**
     * Get all hidden files
     */
    fun getHiddenFiles(): List<File> {
        return safeFolder.listFiles()?.filter { it.isFile } ?: emptyList()
    }

    /**
     * Get hidden files by type
     */
    fun getHiddenFilesByType(type: String): List<File> {
        return getHiddenFiles().filter { file ->
            when (type.lowercase()) {
                "video" -> file.extension.lowercase() in listOf("vh", "mp4", "mkv", "avi", "mov", "3gp")
                "photo" -> file.extension.lowercase() in listOf("vh", "jpg", "jpeg", "png", "gif", "bmp", "webp")
                "audio" -> file.extension.lowercase() in listOf("vh", "mp3", "wav", "aac", "ogg", "flac")
                "document" -> file.extension.lowercase() in listOf("vh", "pdf", "doc", "docx", "txt", "xls", "xlsx")
                else -> true
            }
        }
    }

    /**
     * Restore a hidden file
     */
    fun restoreFile(hiddenFilePath: String, destinationPath: String): Boolean {
        return try {
            val hiddenFile = File(hiddenFilePath)
            if (!hiddenFile.exists()) {
                Log.e(TAG, "❌ Hidden file not found: $hiddenFilePath")
                return false
            }

            // Restore original name
            val originalName = hiddenFile.nameWithoutExtension
            val destFile = File(destinationPath, originalName)

            hiddenFile.copyTo(destFile, overwrite = true)
            hiddenFile.delete()

            // Update MediaStore
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, destFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(destFile))
                put(MediaStore.MediaColumns.SIZE, destFile.length())
            }

            context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"),
                values
            )

            Log.d(TAG, "✅ File restored: ${destFile.absolutePath}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restoring file: ${e.message}")
            false
        }
    }

    /**
     * Delete a hidden file permanently
     */
    fun deleteHiddenFile(hiddenFilePath: String): Boolean {
        return try {
            val file = File(hiddenFilePath)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "✅ Hidden file deleted: $hiddenFilePath")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deleting hidden file: ${e.message}")
            false
        }
    }

    // ===== PRIVATE HELPER FUNCTIONS =====

    /**
     * Create .nomedia file in the safe folder
     * This is the KEY to hiding files from gallery
     */
    private fun createNoMediaFile() {
        val noMediaFile = File(safeFolder, ".nomedia")
        if (!noMediaFile.exists()) {
            noMediaFile.createNewFile()
            Log.d(TAG, "✅ .nomedia file created at: ${noMediaFile.absolutePath}")
        }
    }

    /**
     * Remove file entry from MediaStore
     * This ensures the gallery doesn't see the file
     */
    private fun removeFromMediaStore(filePath: String) {
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(filePath)
            val deletedRows = context.contentResolver.delete(uri, selection, selectionArgs)
            Log.d(TAG, "✅ Removed from MediaStore: $deletedRows rows deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing from MediaStore: ${e.message}")
        }
    }

    /**
     * Get MIME type from file
     */
    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "txt" -> "text/plain"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }
}
