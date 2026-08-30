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
        private const val TARGET_WIDTH = 256
        private const val TARGET_HEIGHT = 144
    }

    fun extractClip(inputPath: String): File? {
        return try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                Log.e(TAG, "❌ Input file not found: $inputPath")
                return null
            }

            val clipFolder = File(context.filesDir, "clips")
            if (!clipFolder.exists()) clipFolder.mkdirs()

            val outputFile = File(clipFolder, "${inputFile.nameWithoutExtension}_preview.mp4")

            val commandArray = arrayOf(
                "-ss", "00:00:00",
                "-i", inputFile.absolutePath,
                "-t", CLIP_DURATION_SECONDS.toString(),
                "-vf", "scale=$TARGET_WIDTH:$TARGET_HEIGHT",
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-c:a", "aac",
                "-b:a", "64k",
                "-y",
                outputFile.absolutePath
            )

            Log.d(TAG, "🎬 Running FFmpeg command with arguments: ${commandArray.joinToString(" ")}")

            val session = FFmpegKit.executeWithArguments(commandArray)

            if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d(TAG, "✅ Clip extracted successfully: ${outputFile.absolutePath}")
                outputFile
            } else {
                Log.e(TAG, "❌ FFmpeg failed with return code: ${session.returnCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during clip extraction", e)
            null
        }
    }
}
