package com.example.ai.context

data class ConversationContext(
    val resolvedContextText: String?,
    val confidence: Float,
    val detectedTopic: String?,
    val isResolved: Boolean
)
