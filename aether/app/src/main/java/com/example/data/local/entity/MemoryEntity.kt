package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String,
    val createdTimestamp: Long,
    val lastUpdatedTimestamp: Long,
    val sourceConversationId: String?,
    val isArchived: Boolean = false,
    val confidenceScore: Int = 1,
    val lastAccessedTimestamp: Long = 0L,
    val accessCount: Int = 0,
    val state: String = "CANDIDATE",
    val temporalType: String = "PERSISTENT",
    val expirationTimestamp: Long = 0L
)
