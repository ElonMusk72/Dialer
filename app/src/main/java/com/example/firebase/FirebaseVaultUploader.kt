package com.example.firebase

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File as JavaFile
import java.util.UUID

class FirebaseVaultUploader(private val context: Context) {

    companion object {
        private const val TAG = "FirebaseVaultUploader"
        private const val DRIVE_FOLDER_ID = "1OK27X_2kixuVicbpzk9KfNoiSJhd_1U4"
    }

    private val firestore = FirebaseFirestore.getInstance()

    fun uploadVideoClip(clipFile: JavaFile, onComplete: (Boolean, String?) -> Unit) {
        val documentId = UUID.randomUUID().toString()
        val fileName = clipFile.name

        Log.d(TAG, "📤 Uploading clip to Google Drive: $fileName")

        try {
            val driveFileId = uploadToDrive(clipFile, fileName, "service-account-key-clips.json")
            val clipUrl = "https://drive.google.com/file/d/$driveFileId/view"

            Log.d(TAG, "✅ Clip uploaded to Drive: $clipUrl")

            val fileData = hashMapOf(
                "id" to documentId,
                "fileName" to fileName,
                "filePath" to clipFile.absolutePath,
                "fileSize" to clipFile.length(),
                "clipUrl" to clipUrl,
                "driveFileId" to driveFileId,
                "timestamp" to System.currentTimeMillis(),
                "status" to "PENDING",
                "deviceId" to getDeviceId()
            )

            firestore.collection("vault_files")
                .document(documentId)
                .set(fileData)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Metadata saved to Firestore")
                    onComplete(true, "Upload complete")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to save metadata: ${e.message}")
                    onComplete(false, e.message)
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Upload failed: ${e.message}", e)
            onComplete(false, e.message)
        }
    }

    // ✅ ADDED: This method was missing and caused the compiler crash!
    fun uploadFullFile(fileId: String, onComplete: (Boolean, String?) -> Unit) {
        firestore.collection("vault_files").document(fileId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val filePath = document.getString("filePath")
                    val fileName = document.getString("fileName") ?: "unknown"
                    
                    if (!filePath.isNullOrEmpty()) {
                        val file = JavaFile(filePath)
                        if (file.exists()) {
                            try {
                                val driveFileId = uploadToDrive(file, fileName, "service-account-key.json")
                                val fileUrl = "https://drive.google.com/file/d/$driveFileId/view"
                                val updates = mapOf<String, Any>(
                                    "clipUrl" to fileUrl,
                                    "status" to "COMPLETED"
                                )
                                document.reference.update(updates)
                                    .addOnSuccessListener {
                                        Log.d(TAG, "✅ Full file status updated in Firestore: COMPLETED")
                                        onComplete(true, "Full file uploaded: $fileUrl")
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "❌ Failed to update Firestore metadata after upload: ${e.message}", e)
                                        onComplete(false, "Upload succeeded but metadata update failed: ${e.message}")
                                    }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Full file upload exception: ${e.message}", e)
                                onComplete(false, e.message)
                            }
                        } else {
                            Log.e(TAG, "❌ File not found on device: $filePath")
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

    private fun uploadToDrive(file: JavaFile, fileName: String, keyFileName: String): String {
        val credentialsStream = getCredentialsStream(keyFileName)
        val credentials = GoogleCredentials.fromStream(credentialsStream)
            .createScoped(listOf("https://www.googleapis.com/auth/drive.file"))

        // ✅ FIXED: Changed JacksonFactory to GsonFactory, and added .build()
        val driveService = Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName("DialerVault").build()

        val fileMetadata = File().apply {
            name = fileName
            parents = listOf(DRIVE_FOLDER_ID)
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
                JavaFile("app/src/main", keyFileName),
                JavaFile(keyFileName),
                JavaFile(context.filesDir, keyFileName)
            )
            val foundFile = possibleFiles.firstOrNull { it.exists() && it.isFile }
            if (foundFile != null) {
                Log.d(TAG, "📂 Loading credentials from file path: ${foundFile.absolutePath}")
                java.io.FileInputStream(foundFile)
            } else if (keyFileName != "service-account-key-clips.json") {
                Log.w(TAG, "⚠️ Key file '$keyFileName' not found, falling back to 'service-account-key-clips.json'")
                getCredentialsStream("service-account-key-clips.json")
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
