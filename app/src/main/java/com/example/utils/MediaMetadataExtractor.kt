package com.example.utils

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

data class MediaMetadata(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val dateModified: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val cameraModel: String = "",
    val dateTaken: Long = 0,
    val framePaths: List<String> = emptyList(),
    val thumbnailPath: String? = null
)

class MediaMetadataExtractor(private val context: android.content.Context) {

    companion object {
        private const val TAG = "MediaMetadataExtractor"
        private const val FRAMES_TO_EXTRACT = 5
        private const val THUMBNAIL_SIZE = 300
    }

    fun extractMetadataAndFrames(filePath: String): MediaMetadata? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "❌ File not found: $filePath")
                return null
            }

            val uri = Uri.fromFile(file)
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: getMimeTypeFromExtension(file.name)
            
            // Create safe folder for extracted data
            val metadataFolder = File(context.filesDir, "SafeFolder/Metadata")
            if (!metadataFolder.exists()) metadataFolder.mkdirs()

            val fileName = file.name
            val baseName = fileName.substringBeforeLast(".")
            
            // Extract thumbnail
            val thumbnailPath = generateThumbnail(file, baseName, metadataFolder)
            
            // Extract video frames if video
            val framePaths = if (mimeType.startsWith("video/")) {
                extractVideoFrames(file, baseName, metadataFolder)
            } else {
                emptyList()
            }

            // Extract EXIF data for photos
            var latitude = 0.0
            var longitude = 0.0
            var cameraModel = ""
            var dateTaken = 0L
            
            if (mimeType.startsWith("image/")) {
                try {
                    val exifInterface = android.media.ExifInterface(file.absolutePath)
                    cameraModel = exifInterface.getAttribute(android.media.ExifInterface.TAG_MODEL) ?: ""
                    dateTaken = try {
                        exifInterface.getAttribute(android.media.ExifInterface.TAG_DATETIME)?.let {
                            // Convert date string to timestamp
                            val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.getDefault())
                            sdf.parse(it)?.time ?: 0
                        } ?: 0
                    } catch (e: Exception) { 0 }
                    
                    // Get GPS
                    val latRef = exifInterface.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF)
                    val lat = exifInterface.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE)
                    val lonRef = exifInterface.getAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF)
                    val lon = exifInterface.getAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE)
                    
                    if (lat != null && lon != null) {
                        latitude = convertToDecimal(lat)
                        longitude = convertToDecimal(lon)
                        if (latRef == "S") latitude = -latitude
                        if (lonRef == "W") longitude = -longitude
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract EXIF data: ${e.message}")
                }
            }

            // Get duration for videos
            var duration = 0L
            if (mimeType.startsWith("video/")) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = durationStr?.toLongOrNull() ?: 0
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not extract duration: ${e.message}")
                }
            }

            return MediaMetadata(
                fileName = fileName,
                filePath = file.absolutePath,
                fileSize = file.length(),
                mimeType = mimeType,
                duration = duration,
                width = 0, // Could extract from exif/video
                height = 0,
                dateModified = file.lastModified(),
                latitude = latitude,
                longitude = longitude,
                cameraModel = cameraModel,
                dateTaken = dateTaken,
                framePaths = framePaths,
                thumbnailPath = thumbnailPath
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting metadata: ${e.message}")
            null
        }
    }

    private fun generateThumbnail(file: File, baseName: String, outputFolder: File): String? {
        return try {
            val uri = Uri.fromFile(file)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            val inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            var sampleSize = 1
            while (options.outWidth / sampleSize > THUMBNAIL_SIZE || 
                   options.outHeight / sampleSize > THUMBNAIL_SIZE) {
                sampleSize *= 2
            }

            val finalOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val newStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(newStream, null, finalOptions)
            newStream?.close()

            if (bitmap != null) {
                val thumbFile = File(outputFolder, "${baseName}_thumb.jpg")
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, java.io.FileOutputStream(thumbFile))
                bitmap.recycle()
                thumbFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail: ${e.message}")
            null
        }
    }

    private fun extractVideoFrames(file: File, baseName: String, outputFolder: File): List<String> {
        val framePaths = mutableListOf<String>()
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0
            
            if (duration > 0) {
                val interval = duration / (FRAMES_TO_EXTRACT + 1)
                for (i in 1..FRAMES_TO_EXTRACT) {
                    val timeUs = (i * interval) * 1000
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val frameFile = File(outputFolder, "${baseName}_frame_$i.jpg")
                        frame.compress(Bitmap.CompressFormat.JPEG, 70, java.io.FileOutputStream(frameFile))
                        framePaths.add(frameFile.absolutePath)
                        frame.recycle()
                    }
                }
            }
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video frames: ${e.message}")
        }
        return framePaths
    }

    private fun convertToDecimal(coord: String): Double {
        try {
            val parts = coord.split(",").map { it.trim() }
            if (parts.size == 3) {
                val degrees = parts[0].toDouble()
                val minutes = parts[1].toDouble()
                val seconds = parts[2].replace("[^\\d.]".toRegex(), "").toDouble()
                return degrees + (minutes / 60.0) + (seconds / 3600.0)
            }
        } catch (e: Exception) {}
        return 0.0
    }

    private fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        if (extension.isNotEmpty()) {
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.lowercase())
            if (!mime.isNullOrEmpty()) return mime
        }
        return "*/*"
    }
}
