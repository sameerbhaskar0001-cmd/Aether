package com.example.data.model

import java.util.UUID

enum class Sender {
    USER,
    ASSISTANT
}

enum class MessageStatus {
    SENDING,
    SENT,
    THINKING,
    ERROR
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT
)
