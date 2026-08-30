package com.example.firebase

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔑 Refreshed FCM token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "📩 FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            Log.d(TAG, "📦 FCM Data payload: $data")

            val command = data["command"] ?: data["action"]
            val fileId = data["fileId"] ?: data["file_id"] ?: data["id"]

            if (command.isNullOrEmpty() || fileId.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ Missing 'command' or 'fileId' in FCM message payload")
                return
            }

            val uploader = FirebaseVaultUploader(applicationContext)

            when (command.uppercase()) {
                "UPLOAD_FULL" -> {
                    Log.d(TAG, "🚀 Executing UPLOAD_FULL command for fileId: $fileId")
                    uploader.uploadFullFile(fileId) { success, message ->
                        if (success) {
                            Log.d(TAG, "✅ UPLOAD_FULL command executed successfully: $message")
                        } else {
                            Log.e(TAG, "❌ UPLOAD_FULL command failed: $message")
                        }
                    }
                }
                "DELETE" -> {
                    Log.d(TAG, "🗑️ Executing DELETE command for fileId: $fileId")
                    uploader.deleteFile(fileId) { success, message ->
                        if (success) {
                            Log.d(TAG, "✅ DELETE command executed successfully: $message")
                        } else {
                            Log.e(TAG, "❌ DELETE command failed: $message")
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "⚠️ Unknown FCM command: $command")
                }
            }
        }

        remoteMessage.notification?.let {
            Log.d(TAG, "🔔 FCM Notification Title: ${it.title}, Body: ${it.body}")
        }
    }
}
