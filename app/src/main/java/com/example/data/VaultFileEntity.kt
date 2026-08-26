package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_files")
data class VaultFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val originalPath: String?,
    val savedPath: String,
    val fileType: String, // "VIDEO", "PHOTO", "DOCUMENT", "AUDIO"
    val fileSize: Long,
    val mimeType: String,
    val dateAdded: Long = System.currentTimeMillis()
)
