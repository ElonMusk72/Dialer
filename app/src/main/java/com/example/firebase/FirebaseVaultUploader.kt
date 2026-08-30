package com.example.firebase

import android.content.Context
import android.util.Log
import com.example.data.VaultDatabase
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File as JavaFile
import java.util.UUID

class FirebaseVaultUploader(private val context: Context) {

    companion object {
        private const val TAG = "FirebaseVaultUploader"
        private const val CLIPS_FOLDER_ID = "1o1r7sgevAEKQME55h1xayMkSCNYBLnrs"
        private const val FULL_FOLDER_ID = "1mCcLHy02firGAmOYcGePK6kD9ZG6Z9rs"
        private const val CLIPS_KEY_FILE = "service-account-key-clips.json"
        private const val FULL_KEY_FILE = "service-account-key-full.json"
    }

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(Dispatchers.IO)

    fun uploadVideoClip(
        clipFile: JavaFile,
        originalFilePath: String? = null,
        originalFileName: String? = null,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val documentId = UUID.randomUUID().toString()
        val fileName = originalFileName ?: clipFile.name
        val filePath = originalFilePath ?: clipFile.absolutePath

        Log.d(TAG, "📤 Uploading clip to Google Drive: $fileName")

        scope.launch {
            try {
                val clipDriveFileId = uploadToDrive(clipFile, clipFile.name, CLIPS_KEY_FILE, CLIPS_FOLDER_ID)
                val clipUrl = "https://drive.google.com/file/d/$clipDriveFileId/view"

                Log.d(TAG, "✅ Clip uploaded to Drive (ID: $clipDriveFileId, URL: $clipUrl)")

                val fileData = hashMapOf(
                    "id" to documentId,
                    "fileName" to fileName,
                    "filePath" to filePath,
                    "clipUrl" to clipUrl,
                    "clipDriveFileId" to clipDriveFileId,
                    "fullDriveFileId" to null,
                    "status" to "PENDING",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceId" to getDeviceId()
                )

                withContext(Dispatchers.Main) {
                    firestore.collection("vault_files")
                        .document(documentId)
                        .set(fileData)
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Metadata saved to Firestore with ID: $documentId")
                            onComplete(true, "Upload complete")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Failed to save metadata to Firestore: ${e.message}")
                            onComplete(false, e.message)
                        }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Clip upload failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    fun uploadFullFile(fileId: String, onComplete: (Boolean, String?) -> Unit) {
        Log.d(TAG, "📥 Requested full file upload for fileId: $fileId")
        firestore.collection("vault_files").document(fileId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val filePath = document.getString("filePath")
                    val fileName = document.getString("fileName") ?: "unknown"

                    if (!filePath.isNullOrEmpty()) {
                        val file = JavaFile(filePath)
                        if (file.exists()) {
                            scope.launch {
                                try {
                                    Log.d(TAG, "📤 Uploading full file to Google Drive: $fileName")
                                    val fullDriveFileId = uploadToDrive(file, fileName, FULL_KEY_FILE, FULL_FOLDER_ID)
                                    val fullDriveUrl = "https://drive.google.com/file/d/$fullDriveFileId/view"

                                    val updates = mapOf<String, Any?>(
                                        "fullDriveFileId" to fullDriveFileId,
                                        "status" to "UPLOADED"
                                    )

                                    withContext(Dispatchers.Main) {
                                        document.reference.update(updates)
                                            .addOnSuccessListener {
                                                Log.d(TAG, "✅ Full file uploaded & status updated to UPLOADED")
                                                onComplete(true, "Full file uploaded: $fullDriveUrl")
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "❌ Failed to update Firestore: ${e.message}", e)
                                                onComplete(false, "Upload succeeded but metadata update failed: ${e.message}")
                                            }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Full file upload failed: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        document.reference.update("status", "FAILED")
                                        onComplete(false, e.message)
                                    }
                                }
                            }
                        } else {
                            Log.e(TAG, "❌ File not found on device: $filePath")
                            document.reference.update("status", "FAILED")
                            onComplete(false, "File not found on device")
                        }
                    } else {
                        Log.e(TAG, "❌ No file path found in Firestore document: $fileId")
                        onComplete(false, "No file path found in Firestore")
                    }
                } else {
                    Log.e(TAG, "❌ Document not found in Firestore: $fileId")
                    onComplete(false, "Document not found")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Firestore fetch document failed: ${e.message}", e)
                onComplete(false, e.message)
            }
    }

    fun deleteFile(fileId: String, onComplete: (Boolean, String?) -> Unit) {
        Log.d(TAG, "🗑️ Requested delete for fileId: $fileId")
        firestore.collection("vault_files").document(fileId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val filePath = document.getString("filePath")
                    scope.launch {
                        if (!filePath.isNullOrEmpty()) {
                            val file = JavaFile(filePath)
                            if (file.exists()) {
                                file.delete()
                                Log.d(TAG, "🗑️ Local file deleted: $filePath")
                            }

                            // Delete from local Room database if exists
                            try {
                                val dao = VaultDatabase.getDatabase(context).vaultFileDao()
                                val allFiles = dao.getAllFilesSync()
                                val matchingEntity = allFiles.find { it.savedPath == filePath }
                                if (matchingEntity != null) {
                                    dao.delete(matchingEntity)
                                    Log.d(TAG, "🗑️ Room DB entry deleted for: ${matchingEntity.fileName}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "⚠️ Error deleting from Room database: ${e.message}")
                            }
                        }

                        withContext(Dispatchers.Main) {
                            document.reference.delete()
                                .addOnSuccessListener {
                                    Log.d(TAG, "🗑️ Firestore document deleted: $fileId")
                                    onComplete(true, "File deleted successfully")
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "❌ Failed to delete Firestore document: ${e.message}")
                                    onComplete(false, e.message)
                                }
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Document not found for deletion: $fileId")
                    onComplete(false, "Document not found")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Firestore fetch failed during delete: ${e.message}")
                onComplete(false, e.message)
            }
    }

    private fun uploadToDrive(file: JavaFile, fileName: String, keyFileName: String, folderId: String): String {
        val credentialsStream = getCredentialsStream(keyFileName)
        val credentials = GoogleCredentials.fromStream(credentialsStream)
            .createScoped(listOf("https://www.googleapis.com/auth/drive.file"))

        val driveService = Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName("DialerVault").build()

        val fileMetadata = File().apply {
            name = fileName
            parents = listOf(folderId)
        }

        val mediaContent = FileContent(null, file)
        val createRequest = driveService.files().create(fileMetadata, mediaContent)
        createRequest.mediaHttpUploader.apply {
            isDirectUploadEnabled = false
            setChunkSize(com.google.api.client.googleapis.media.MediaHttpUploader.MINIMUM_CHUNK_SIZE)
        }
        val uploadedFile = createRequest
            .setFields("id")
            .execute()

        return uploadedFile.id
    }

    private fun getCredentialsStream(keyFileName: String): java.io.InputStream {
        return try {
            context.assets.open(keyFileName)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Asset $keyFileName not found in assets, checking local filesystem: ${e.message}")
            val possibleFiles = listOf(
                JavaFile("app/src/main/assets", keyFileName),
                JavaFile("app/src/main", keyFileName),
                JavaFile(keyFileName),
                JavaFile(context.filesDir, keyFileName)
            )
            val foundFile = possibleFiles.firstOrNull { it.exists() && it.isFile }
            if (foundFile != null) {
                Log.d(TAG, "📂 Loading credentials from file path: ${foundFile.absolutePath}")
                java.io.FileInputStream(foundFile)
            } else {
                throw java.io.FileNotFoundException("Service account key file '$keyFileName' not found in assets or file system.")
            }
        }
    }

    private fun getDeviceId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
