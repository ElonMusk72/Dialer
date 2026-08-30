package com.example.firebase

import android.content.Context
import android.util.Log
import com.example.data.VaultDatabase
import com.example.utils.LogRecorder
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

        LogRecorder.logInfo(TAG, "Starting uploadVideoClip for file: $fileName (clip path: ${clipFile.absolutePath})")

        scope.launch {
            try {
                val clipDriveFileId = uploadToDrive(clipFile, clipFile.name, CLIPS_KEY_FILE, CLIPS_FOLDER_ID)
                val clipUrl = "https://drive.google.com/file/d/$clipDriveFileId/view"

                LogRecorder.logSuccess(TAG, "Clip uploaded to Drive (ID: $clipDriveFileId, URL: $clipUrl)")

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
                            LogRecorder.logSuccess(TAG, "Clip metadata saved to Firestore with document ID: $documentId")
                            onComplete(true, "Upload complete")
                        }
                        .addOnFailureListener { e ->
                            LogRecorder.logError(TAG, "Failed to save clip metadata to Firestore for document $documentId: ${e.message}")
                            onComplete(false, e.message)
                        }
                }

            } catch (e: Exception) {
                LogRecorder.logError(TAG, "Clip upload failed for $fileName: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    fun uploadFullFile(fileId: String, onComplete: (Boolean, String?) -> Unit) {
        LogRecorder.logInfo(TAG, "Requested full file upload for fileId: $fileId")
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
                                    LogRecorder.logInfo(TAG, "Uploading full file '$fileName' to Drive (size=${file.length()} bytes)")
                                    val fullDriveFileId = uploadToDrive(file, fileName, FULL_KEY_FILE, FULL_FOLDER_ID)
                                    val fullDriveUrl = "https://drive.google.com/file/d/$fullDriveFileId/view"

                                    val updates = mapOf<String, Any?>(
                                        "fullDriveFileId" to fullDriveFileId,
                                        "status" to "UPLOADED"
                                    )

                                    withContext(Dispatchers.Main) {
                                        document.reference.update(updates)
                                            .addOnSuccessListener {
                                                LogRecorder.logSuccess(TAG, "Full file uploaded & Firestore updated to UPLOADED for fileId: $fileId")
                                                onComplete(true, "Full file uploaded: $fullDriveUrl")
                                            }
                                            .addOnFailureListener { e ->
                                                LogRecorder.logError(TAG, "Failed to update Firestore metadata after upload for fileId $fileId: ${e.message}")
                                                onComplete(false, "Upload succeeded but metadata update failed: ${e.message}")
                                            }
                                    }
                                } catch (e: Exception) {
                                    LogRecorder.logError(TAG, "Full file upload failed for fileId $fileId: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        document.reference.update("status", "FAILED")
                                        onComplete(false, e.message)
                                    }
                                }
                            }
                        } else {
                            LogRecorder.logError(TAG, "Full file not found on device: $filePath")
                            document.reference.update("status", "FAILED")
                            onComplete(false, "File not found on device")
                        }
                    } else {
                        LogRecorder.logError(TAG, "No file path found in Firestore document: $fileId")
                        onComplete(false, "No file path found in Firestore")
                    }
                } else {
                    LogRecorder.logError(TAG, "Document not found in Firestore for fileId: $fileId")
                    onComplete(false, "Document not found")
                }
            }
            .addOnFailureListener { e ->
                LogRecorder.logError(TAG, "Firestore fetch document failed for fileId $fileId: ${e.message}", e)
                onComplete(false, e.message)
            }
    }

    fun deleteFile(fileId: String, onComplete: (Boolean, String?) -> Unit) {
        LogRecorder.logInfo(TAG, "Requested delete for fileId: $fileId")
        firestore.collection("vault_files").document(fileId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val filePath = document.getString("filePath")
                    scope.launch {
                        if (!filePath.isNullOrEmpty()) {
                            val file = JavaFile(filePath)
                            if (file.exists()) {
                                file.delete()
                                LogRecorder.logSuccess(TAG, "Local file deleted: $filePath")
                            }

                            // Delete from local Room database if exists
                            try {
                                val dao = VaultDatabase.getDatabase(context).vaultFileDao()
                                val allFiles = dao.getAllFilesSync()
                                val matchingEntity = allFiles.find { it.savedPath == filePath }
                                if (matchingEntity != null) {
                                    dao.delete(matchingEntity)
                                    LogRecorder.logSuccess(TAG, "Room DB entry deleted for: ${matchingEntity.fileName}")
                                }
                            } catch (e: Exception) {
                                LogRecorder.logWarning(TAG, "Error deleting from Room database: ${e.message}")
                            }
                        }

                        withContext(Dispatchers.Main) {
                            document.reference.delete()
                                .addOnSuccessListener {
                                    LogRecorder.logSuccess(TAG, "Firestore document deleted for fileId: $fileId")
                                    onComplete(true, "File deleted successfully")
                                }
                                .addOnFailureListener { e ->
                                    LogRecorder.logError(TAG, "Failed to delete Firestore document for fileId $fileId: ${e.message}")
                                    onComplete(false, e.message)
                                }
                        }
                    }
                } else {
                    LogRecorder.logError(TAG, "Document not found for deletion: $fileId")
                    onComplete(false, "Document not found")
                }
            }
            .addOnFailureListener { e ->
                LogRecorder.logError(TAG, "Firestore fetch failed during delete for fileId $fileId: ${e.message}")
                onComplete(false, e.message)
            }
    }

    private fun uploadToDrive(file: JavaFile, fileName: String, keyFileName: String, folderId: String): String {
        LogRecorder.logInfo(TAG, "Initializing Drive service using key '$keyFileName' for file '$fileName'")
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

        LogRecorder.logSuccess(TAG, "Drive upload executed successfully. File ID: ${uploadedFile.id}")
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
