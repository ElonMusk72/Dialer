package com.example.commands

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class CommandAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "CommandAnalyzer"
        private const val COMMANDS_COLLECTION = "commands"
    }

    private val firestore = FirebaseFirestore.getInstance()

    fun analyzeAndExecute(commandId: String) {
        Log.d(TAG, "🔍 Analyzing command: $commandId")

        firestore.collection(COMMANDS_COLLECTION)
            .document(commandId)
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    Log.e(TAG, "❌ Command not found: $commandId")
                    updateCommandStatus(commandId, "FAILED", "Command not found")
                    return@addOnSuccessListener
                }

                val action = document.getString("action")
                val status = document.getString("status")
                val parameters = document.get("parameters") as? Map<String, String>

                if (action.isNullOrEmpty()) {
                    Log.e(TAG, "❌ No action in command: $commandId")
                    updateCommandStatus(commandId, "FAILED", "No action specified")
                    return@addOnSuccessListener
                }

                // Check if already processed
                if (status == "COMPLETED" || status == "FAILED") {
                    Log.d(TAG, "⏭️ Already processed: $status")
                    return@addOnSuccessListener
                }

                Log.d(TAG, "📋 Action: $action")
                Log.d(TAG, "📋 Parameters: $parameters")

                // Execute the command
                CommandExecutor(context).executeCommand(commandId, action, parameters)

            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error fetching command: ${e.message}")
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
