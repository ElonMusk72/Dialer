package com.example.utils

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRecorder {

    @Volatile
var logFilePath: String = "/storage/emulated/0/Android/data/com.aistudio.dialer.app/files/logs/app_logs.txt"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun writeLog(type: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        val formattedLog = "[$timestamp] $type | $tag | $message"

        // Also output to android.util.Log for Logcat if available
        try {
            when (type) {
                "INFO" -> Log.i(tag, message, throwable)
                "DEBUG" -> Log.d(tag, message, throwable)
                "ERROR" -> Log.e(tag, message, throwable)
                "SUCCESS" -> Log.i(tag, "SUCCESS: $message", throwable)
                "WARNING" -> Log.w(tag, message, throwable)
                else -> Log.d(tag, message, throwable)
            }
        } catch (_: Throwable) {
            // Ignore Logcat failure (e.g. standard JVM unit tests without Android mock)
        }

        try {
            val file = File(logFilePath)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            synchronized(this) {
                FileWriter(file, true).use { writer ->
                    writer.appendLine(formattedLog)
                    throwable?.let { t ->
                        val printWriter = PrintWriter(writer)
                        t.printStackTrace(printWriter)
                        printWriter.flush()
                    }
                }
            }
        } catch (e: Exception) {
            // Safe fallback
            try {
                Log.e("LogRecorder", "Failed to write log to file: ${e.message}", e)
            } catch (_: Throwable) {}
        }
    }

    fun logInfo(tag: String, message: String) {
        writeLog("INFO", tag, message)
    }

    fun logDebug(tag: String, message: String) {
        writeLog("DEBUG", tag, message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("ERROR", tag, message, throwable)
    }

    fun logSuccess(tag: String, message: String) {
        writeLog("SUCCESS", tag, message)
    }

    fun logWarning(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("WARNING", tag, message, throwable)
    }
}
