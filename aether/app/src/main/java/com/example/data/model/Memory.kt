package com.example.data.model

data class Memory(
    val id: String,
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
