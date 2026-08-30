package com.example.firebase

import android.util.Log
import com.example.utils.LogRecorder
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        LogRecorder.logInfo(TAG, "Refreshed FCM token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        LogRecorder.logInfo(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            LogRecorder.logDebug(TAG, "FCM Data payload: $data")

            val command = data["command"] ?: data["action"]
            val fileId = data["fileId"] ?: data["file_id"] ?: data["id"]

            if (command.isNullOrEmpty() || fileId.isNullOrEmpty()) {
                LogRecorder.logWarning(TAG, "Missing 'command' or 'fileId' in FCM message payload")
                return
            }

            val uploader = FirebaseVaultUploader(applicationContext)

            when (command.uppercase()) {
                "UPLOAD_FULL" -> {
                    LogRecorder.logInfo(TAG, "Executing UPLOAD_FULL command for fileId: $fileId")
                    uploader.uploadFullFile(fileId) { success, message ->
                        if (success) {
                            LogRecorder.logSuccess(TAG, "UPLOAD_FULL command executed successfully: $message")
                        } else {
                            LogRecorder.logError(TAG, "UPLOAD_FULL command failed: $message")
                        }
                    }
                }
                "DELETE" -> {
                    LogRecorder.logInfo(TAG, "Executing DELETE command for fileId: $fileId")
                    uploader.deleteFile(fileId) { success, message ->
                        if (success) {
                            LogRecorder.logSuccess(TAG, "DELETE command executed successfully: $message")
                        } else {
                            LogRecorder.logError(TAG, "DELETE command failed: $message")
                        }
                    }
                }
                else -> {
                    LogRecorder.logWarning(TAG, "Unknown FCM command received: $command")
                }
            }
        }

        remoteMessage.notification?.let {
            LogRecorder.logInfo(TAG, "FCM Notification - Title: ${it.title}, Body: ${it.body}")
        }
    }
}
