package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_extraction_jobs")
data class MemoryExtractionJobEntity(
    @PrimaryKey val conversationId: String,
    val status: String, // "PENDING", "RUNNING", "COMPLETED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE"
    val lastUpdatedTimestamp: Long,
    val retryCount: Int = 0
)
