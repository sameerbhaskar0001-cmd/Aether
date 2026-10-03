package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val content: String,
    val sender: String, // "USER" or "ASSISTANT"
    val timestamp: Long,
    val status: String // "SENDING", "SENT", "THINKING", "ERROR"
)
