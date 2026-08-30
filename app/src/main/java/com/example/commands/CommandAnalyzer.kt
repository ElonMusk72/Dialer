package com.example.commands

import android.content.Context
import android.util.Log
import com.example.utils.LogRecorder
import com.google.firebase.firestore.FirebaseFirestore

class CommandAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "CommandAnalyzer"
        private const val COMMANDS_COLLECTION = "commands"
    }

    private val firestore = FirebaseFirestore.getInstance()

    fun analyzeAndExecute(commandId: String) {
        LogRecorder.logInfo(TAG, "Analyzing command: $commandId")

        firestore.collection(COMMANDS_COLLECTION)
            .document(commandId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    LogRecorder.logError(TAG, "Command document not found for commandId: $commandId")
                    updateCommandStatus(commandId, "FAILED", "Command not found")
                    return@addOnSuccessListener
                }

                val action = document.getString("action")
                val status = document.getString("status")
                val parameters = document.get("parameters") as? Map<String, String>

                if (action.isNullOrEmpty()) {
                    LogRecorder.logError(TAG, "No action specified in command document: $commandId")
                    updateCommandStatus(commandId, "FAILED", "No action specified")
                    return@addOnSuccessListener
                }

                // Check if already processed
                if (status == "COMPLETED" || status == "FAILED") {
                    LogRecorder.logDebug(TAG, "Command $commandId already processed with status: $status")
                    return@addOnSuccessListener
                }

                LogRecorder.logInfo(TAG, "Command $commandId analyzed - Action: $action, Params: $parameters")

                // Execute the command
                CommandExecutor(context).executeCommand(commandId, action, parameters)

            }
            .addOnFailureListener { e ->
                LogRecorder.logError(TAG, "Error fetching command $commandId: ${e.message}", e)
                updateCommandStatus(commandId, "FAILED", e.message)
            }
    }

    private fun updateCommandStatus(commandId: String, status: String, message: String? = null) {
        val firestore = FirebaseFirestore.getInstance()
        val updates = hashMapOf<String, Any>(
            "status" to status,
            "completedAt" to System.currentTimeMillis()
        )
        message?.let { updates["result"] = it }
        
        firestore.collection(COMMANDS_COLLECTION)
            .document(commandId)
            .update(updates)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Command status updated to: $status")
            }
    }
}
