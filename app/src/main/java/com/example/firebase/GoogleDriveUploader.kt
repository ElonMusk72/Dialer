package com.example.firebase

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File as JavaFile
import java.io.FileInputStream

class GoogleDriveUploader(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveUploader"
        private const val DRIVE_FOLDER_ID = "YOUR_GOOGLE_DRIVE_FOLDER_ID" // Replace with your folder ID
    }

    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Upload a full file to Google Drive
     * Called from dashboard selection
     */
    fun uploadToDrive(fileId: String) {
        Log.d(TAG, "☁️ Uploading file to Google Drive: $fileId")

        firestore.collection("vault_files")
            .document(fileId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val filePath = document.getString("filePath")
                    val fileName = document.getString("fileName") ?: "unknown"
                    
                    if (filePath != null) {
                        val file = JavaFile(filePath)
                        if (file.exists()) {
                            try {
                                val driveFileId = uploadFileToDrive(file, fileName)
                                val driveUrl = "https://drive.google.com/file/d/$driveFileId/view"
                                
                                // Update Firestore with Drive URL
                                document.reference.update(
                                    "driveFileId" to driveFileId,
                                    "driveUrl" to driveUrl,
                                    "status" to "UPLOADED",
                                    "isUploaded" to true,
                                    "uploadedAt" to System.currentTimeMillis()
                                )
                                
                                Log.d(TAG, "✅ Uploaded to Drive: $driveUrl")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Drive upload failed: ${e.message}")
                            }
                        } else {
                            Log.e(TAG, "❌ File not found: $filePath")
                        }
                    }
                }
            }
    }

    private fun uploadFileToDrive(file: JavaFile, fileName: String): String {
        // Load credentials from assets
        val credentialsStream = context.assets.open("service-account-key.json")
        val credentials = GoogleCredentials.fromStream(credentialsStream)
            .createScoped(listOf("https://www.googleapis.com/auth/drive.file"))
        
        val driveService = Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            JacksonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName("VaultApp")

        val fileMetadata = File().apply {
            name = fileName
            parents = listOf(DRIVE_FOLDER_ID)
        }

        val mediaContent = FileInputStream(file)
        val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
            .setFields("id")
            .execute()

        return uploadedFile.id
    }
}
