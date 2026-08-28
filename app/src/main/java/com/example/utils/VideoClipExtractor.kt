package com.example.utils

import android.content.Context
import android.util.Log
import com.arthenica.mobileffmpeg.FFmpeg
import java.io.File

class VideoClipExtractor(private val context: Context) {

    companion object {
        private const val TAG = "VideoClipExtractor"
        private const val CLIP_DURATION_SECONDS = 5  // ✅ Changed to 5 seconds
        private const val TARGET_WIDTH = 256
        private const val TARGET_HEIGHT = 144
    }

    /**
     * Extract first 5 seconds of video at 144p resolution
     */
    fun extractClip(inputPath: String): File? {
        return try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                Log.e(TAG, "❌ Input file not found")
                return null
            }

            val clipFolder = File(context.filesDir, "clips")
            if (!clipFolder.exists()) clipFolder.mkdirs()

            val outputFile = File(clipFolder, "${inputFile.nameWithoutExtension}_preview.mp4")

            // ✅ FFmpeg command: extract 5 seconds AND scale to 144p
            val command = arrayOf(
                "-i", inputFile.absolutePath,          // Input file
                "-t", CLIP_DURATION_SECONDS.toString(), // Extract 5 seconds
                "-vf", "scale=$TARGET_WIDTH:$TARGET_HEIGHT", // Scale to 144p
                "-c:v", "libx264",                     // Video codec
                "-preset", "ultrafast",                // Fast encoding
                "-c:a", "aac",                         // Audio codec
                "-b:a", "64k",                         // Low audio bitrate
                "-y",                                  // Overwrite output
                outputFile.absolutePath                // Output file
            )

            Log.d(TAG, "🎬 Running FFmpeg command: ${command.joinToString(" ")}")

            val returnCode = FFmpeg.execute(command)

            if (returnCode == 0) {
                Log.d(TAG, "✅ Clip extracted: ${outputFile.absolutePath}")
                Log.d(TAG, "📊 File size: ${outputFile.length() / 1024} KB")
                outputFile
            } else {
                Log.e(TAG, "❌ FFmpeg failed with code: $returnCode")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to extract clip: ${e.message}")
            null
        }
    }

    /**
     * Get clip duration (for preview)
     */
    fun getClipDuration(): Int = CLIP_DURATION_SECONDS

    /**
     * Get target resolution
     */
    fun getTargetResolution(): String = "${TARGET_WIDTH}x${TARGET_HEIGHT}"
}
