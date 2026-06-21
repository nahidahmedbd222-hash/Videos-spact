package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "split_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalFileName: String,
    val originalDurationMs: Long,
    val originalSize: Long,
    val splitPartCount: Int,
    val intervalText: String,
    val outputFolderPath: String,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable
