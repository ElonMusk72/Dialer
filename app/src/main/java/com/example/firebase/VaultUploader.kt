package com.example.firebase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.utils.MediaMetadata
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.UUID

class VaultUploader(private val context: Context) {

    companion object {
        private const val TAG = "VaultUploader"
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    fun uploadVaultFile(
        vaultFile: File,
        metadata: MediaMetadata,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val documentId = UUID.randomUUID().toString()
        val fileName = vaultFile.name
        val fileType = getFileType(metadata.mimeType)

        Log.d(TAG, "📤 Uploading: $fileName (Type: $fileType)")

        // Upload thumbnail and frames to Firebase Storage
        var thumbnailUrl = ""
        var frameUrls = emptyList<String>()

        // 1. Upload thumbnail
        metadata.thumbnailPath?.let { thumbPath ->
            val thumbFile = File(thumbPath)
            if (thumbFile.exists()) {
                val thumbRef = storage.reference.child("vault_thumbnails/${documentId}_thumb.jpg")
                uploadFileToStorage(thumbRef, thumbFile) { url ->
                    thumbnailUrl = url
                    Log.d(TAG, "✅ Thumbnail uploaded")
                    checkAndSaveToFirestore(documentId, metadata, thumbnailUrl, frameUrls, fileType)
                }
            }
        }

        // 2. Upload video frames
        if (metadata.framePaths.isNotEmpty()) {
            val frameUrlsList = mutableListOf<String>()
            metadata.framePaths.forEachIndexed { index, framePath ->
                val frameFile = File(framePath)
                if (frameFile.exists()) {
                    val frameRef = storage.reference.child("vault_frames/${documentId}_frame_$index.jpg")
                    uploadFileToStorage(frameRef, frameFile) { url ->
                        frameUrlsList.add(url)
                        Log.d(TAG, "✅ Frame $index uploaded")
                        if (frameUrlsList.size == metadata.framePaths.size) {
                            frameUrls = frameUrlsList
                            checkAndSaveToFirestore(documentId, metadata, thumbnailUrl, frameUrls, fileType)
                        }
                    }
                }
            }
        } else {
            // No frames (photo/audio), save directly
            checkAndSaveToFirestore(documentId, metadata, thumbnailUrl, frameUrls, fileType)
        }

        // 3. Save metadata to Firestore
        checkAndSaveToFirestore(documentId, metadata, thumbnailUrl, frameUrls, fileType)

        // 4. Upload full file to Google Drive (handled separately)
        // This is called from the dashboard selection
    }

    private fun uploadFileToStorage(
        storageRef: com.google.firebase.storage.StorageReference,
        file: File,
        onSuccess: (String) -> Unit
    ) {
        storageRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    onSuccess(uri.toString())
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Upload failed: ${e.message}")
            }
    }

    private fun checkAndSaveToFirestore(
        documentId: String,
        metadata: MediaMetadata,
        thumbnailUrl: String,
        frameUrls: List<String>,
        fileType: String
    ) {
        val data = mutableMapOf<String, Any>(
            "id" to documentId,
            "fileName" to metadata.fileName,
            "filePath" to metadata.filePath,
            "fileSize" to metadata.fileSize,
            "mimeType" to metadata.mimeType,
            "type" to fileType,
            "duration" to metadata.duration,
            "cameraModel" to metadata.cameraModel,
            "dateTaken" to metadata.dateTaken,
            "latitude" to metadata.latitude,
            "longitude" to metadata.longitude,
            "dateModified" to metadata.dateModified,
            "timestamp" to System.currentTimeMillis(),
            "thumbnailUrl" to thumbnailUrl,
            "frameUrls" to frameUrls,
            "status" to "PENDING", // PENDING, UPLOADED, DELETED
            "isUploaded" to false
        )

        firestore.collection("vault_files")
            .document(documentId)
            .set(data)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Metadata saved to Firestore: $documentId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to save metadata: ${e.message}")
            }
    }

    private fun getFileType(mimeType: String): String {
        return when {
            mimeType.startsWith("video/") -> "VIDEO"
            mimeType.startsWith("image/") -> "PHOTO"
            mimeType.startsWith("audio/") -> "AUDIO"
            else -> "DOCUMENT"
        }
    }
}
