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

    const val STORAGE_PERMISSION_REQUEST_CODE = 2001

    /**
     * Checks whether "All Files Access" (or storage permissions on older Android) is granted.
     * - Android 11+ (API 30+): Environment.isExternalStorageManager()
     * - Android 10 and below: READ_EXTERNAL_STORAGE (and WRITE_EXTERNAL_STORAGE)
     */
    fun isAllFilesAccessGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
    }

    /**
     * Opens system Settings page or launches runtime permission request for All Files Access.
     */
    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    activity.startActivity(fallbackIntent)
                } catch (ex: Exception) {
                    Toast.makeText(activity, "Unable to open All Files Access settings.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
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
     */
    fun showAllFilesAccessDialog(
        activity: Activity,
        onGranted: (() -> Unit)? = null
    ) {
        val message = "This app needs All Files Access to:\n" +
                "  • Hide and protect your files\n" +
                "  • Backup your files\n" +
                "  • Scan and organize your files"

        AlertDialog.Builder(activity)
            .setTitle("📁 All Files Access Needed")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("Grant Access") { dialog, _ ->
                dialog.dismiss()
                requestAllFilesAccess(activity)
                onGranted?.invoke()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
