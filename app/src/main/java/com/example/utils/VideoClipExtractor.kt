package com.example.utils

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class VideoClipExtractor(private val context: Context) {

    companion object {
        private const val TAG = "VideoClipExtractor"
        private const val CLIP_DURATION_SECONDS = 5
    }

    fun extractClip(inputPath: String): File? {
        LogRecorder.logInfo(TAG, "Starting clip extraction for: $inputPath")
        return try {
            val inputFile = File(inputPath)
            val exists = inputFile.exists()
            LogRecorder.logDebug(TAG, "File exists: $exists")
            if (!exists) {
                LogRecorder.logError(TAG, "Input file not found: $inputPath")
                return null
            }
            LogRecorder.logDebug(TAG, "Input file size: ${inputFile.length()} bytes")

            val clipFolder = File(context.filesDir, "clips")
            if (!clipFolder.exists()) clipFolder.mkdirs()

            val outputFile = File(clipFolder, "${inputFile.nameWithoutExtension}_preview.mp4")

            val commandArray = arrayOf(
                "-y",
                "-ss", "00:00:00",
                "-i", inputFile.absolutePath,
                "-t", CLIP_DURATION_SECONDS.toString(),
                "-c", "copy",
                outputFile.absolutePath
            )

            val commandStr = commandArray.joinToString(" ")
            LogRecorder.logInfo(TAG, "Starting clip extraction: ${inputFile.absolutePath}")
            LogRecorder.logDebug(TAG, "File exists: $exists")
            LogRecorder.logDebug(TAG, "File size: ${inputFile.length()} bytes")
            LogRecorder.logDebug(TAG, "Running command: $commandStr")

            val session = FFmpegKit.executeWithArguments(commandArray)
            val returnCode = session.returnCode
            val outputLogs = session.allLogsAsString ?: session.output ?: ""

            LogRecorder.logDebug(TAG, "Return code: $returnCode")
            if (outputLogs.isNotBlank()) {
                LogRecorder.logDebug(TAG, "FFmpeg output: $outputLogs")
            }

            if (ReturnCode.isSuccess(returnCode)) {
                LogRecorder.logSuccess(TAG, "✅ Clip extracted: ${outputFile.absolutePath}")
                outputFile
            } else {
                val failStackTrace = session.failStackTrace ?: ""
                LogRecorder.logError(TAG, "❌ FFmpeg failed: Return code $returnCode. Output: $outputLogs $failStackTrace")
                null
            }
        } catch (e: Exception) {
            LogRecorder.logError(TAG, "Exception during clip extraction for: $inputPath", e)
            null
        }
    }
}
