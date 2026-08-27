package com.example.firebase

import android.content.Context
import android.util.Log
import com.example.utils.SupabaseClient
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class VaultMetadata(
    val file_name: String,
    val file_path: String? = null,
    val file_size: Long = 0,
    val mime_type: String = "",
    val file_type: String = "OTHER",
    val duration: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val camera_model: String = "",
    val date_taken: Long = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val thumbnail_url: String = "",
    val frame_urls: List<String> = emptyList(),
    val status: String = "PENDING",
    val device_id: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class SupabaseVaultUploader(private val context: Context) {

    private val supabase = SupabaseClient.client
    private val BUCKET_NAME = "vault_files"

    suspend fun uploadMetadata(metadata: VaultMetadata): String {
        return withContext(Dispatchers.IO) {
            try {
                val result = supabase.postgrest["vault_files"]
                    .insert(metadata)
                    .decodeSingle<Map<String, Any>>()
                
                val id = result["id"] as? String ?: UUID.randomUUID().toString()
                Log.d("SupabaseUploader", "✅ Uploaded metadata: $id")
                id
            } catch (e: Exception) {
                Log.e("SupabaseUploader", "❌ Failed: ${e.message}")
                throw e
            }
        }
    }

    suspend fun uploadFile(file: File, path: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val storage = supabase.storage
                val bucket = storage.from(BUCKET_NAME)
                
                val bytes = file.readBytes()
                bucket.upload(path, bytes, forceMultipart = true)
                
                val publicUrl = bucket.getPublicUrl(path)
                Log.d("SupabaseUploader", "✅ Uploaded: $publicUrl")
                publicUrl
            } catch (e: Exception) {
                Log.e("SupabaseUploader", "❌ Failed: ${e.message}")
                throw e
            }
        }
    }

    suspend fun uploadThumbnail(file: File, fileId: String): String {
        return uploadFile(file, "thumbnails/${fileId}_thumb.jpg")
    }

    suspend fun uploadFrames(frames: List<File>, fileId: String): List<String> {
        val urls = mutableListOf<String>()
        frames.forEachIndexed { index, frame ->
            val url = uploadFile(frame, "frames/${fileId}_frame_$index.jpg")
            urls.add(url)
        }
        return urls
    }
}
