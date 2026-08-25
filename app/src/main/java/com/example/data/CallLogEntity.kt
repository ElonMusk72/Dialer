package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_logs")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val callerName: String? = null,
    val callType: String, // INCOMING, OUTGOING, MISSED
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0
)
