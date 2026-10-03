package com.example.data.model

data class Conversation(
    val id: String,
    val title: String,
    val lastUpdated: Long,
    val isIncognito: Boolean = false
)
