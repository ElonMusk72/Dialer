package com.example.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object StoragePermissionUtils {

    private const val TAG = "StoragePermissionUtils"
    const val STORAGE_PERMISSION_REQUEST_CODE = 2001

    /**
     * Checks whether "All Files Access" (or storage permissions on older Android) is granted.
     * - Android 11+ (API 30+): Environment.isExternalStorageManager()
     * - Android 10 and below: READ_EXTERNAL_STORAGE (and WRITE_EXTERNAL_STORAGE)
     */
    fun isAllFilesAccessGranted(context: Context): Boolean {
        val isGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
        LogRecorder.logDebug(TAG, "isAllFilesAccessGranted: $isGranted (SDK ${Build.VERSION.SDK_INT})")
        return isGranted
    }

    /**
     * Opens system Settings page or launches runtime permission request for All Files Access.
     */
    fun requestAllFilesAccess(activity: Activity) {
        LogRecorder.logInfo(TAG, "requestAllFilesAccess called")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                LogRecorder.logInfo(TAG, "Launched ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION intent")
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(fallbackIntent)
                    LogRecorder.logInfo(TAG, "Launched fallback ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION intent")
                } catch (ex: Exception) {
                    LogRecorder.logError(TAG, "Unable to launch settings for All Files Access", ex)
                    Toast.makeText(activity, "Unable to open All Files Access settings.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            LogRecorder.logInfo(TAG, "Requesting legacy runtime permissions READ/WRITE_EXTERNAL_STORAGE")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Shows an informative explanation dialog before redirecting to grant permission.
     * Title: "📁 All Files Access Needed"
     * Message: "This app needs All Files Access to hide and protect your files, photos, and videos in the vault."
     * Button: "Grant Access" → opens Settings
     */
    fun showAllFilesAccessDialog(
        activity: Activity,
        onGrantClicked: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        LogRecorder.logInfo(TAG, "Showing All Files Access dialog")
        val message = "This app needs All Files Access to hide and protect your files, photos, and videos in the vault."

        AlertDialog.Builder(activity)
            .setTitle("📁 All Files Access Needed")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Grant Access") { dialog, _ ->
                LogRecorder.logInfo(TAG, "User clicked Grant Access in dialog")
                dialog.dismiss()
                requestAllFilesAccess(activity)
                onGrantClicked?.invoke()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                LogRecorder.logInfo(TAG, "User clicked Cancel in dialog")
                dialog.dismiss()
                onDismissed?.invoke()
            }
            .show()
    }
}
