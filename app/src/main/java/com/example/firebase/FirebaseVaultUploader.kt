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
import java.util.UUID

class FirebaseVaultUploader(private val context: Context) {

    companion object {
        private const val TAG = "FirebaseVaultUploader"
        // ✅ UPDATED: Your actual Google Drive folder ID for 5-second clips
        private const val DRIVE_FOLDER_ID = "165hX9VDGvJZuNDFhGO1hxV2gT5Sxxyiq"
    }

    private val firestore = FirebaseFirestore.getInstance()

    fun uploadVideoClip(clipFile: JavaFile, onComplete: (Boolean, String?) -> Unit) {
        val documentId = UUID.randomUUID().toString()
        val fileName = clipFile.name

        Log.d(TAG, "📤 Uploading clip to Google Drive: $fileName")

        try {
            // ✅ USE ACCOUNT 2 FOR CLIPS (service-account-key-clips.json)
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
            Log.e(TAG, "❌ Upload failed: ${e.message}")
            onComplete(false, e.message)
        }
    }

    private fun uploadToDrive(file: JavaFile, fileName: String, keyFileName: String): String {
        // Load credentials from assets
        val credentialsStream = context.assets.open(keyFileName)
        val credentials = GoogleCredentials.fromStream(credentialsStream)
            .createScoped(listOf("https://www.googleapis.com/auth/drive.file"))

        val driveService = Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            JacksonFactory.getDefaultInstance(),
            HttpCredentialsAdapter(credentials)
        ).setApplicationName("DialerVault")

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
