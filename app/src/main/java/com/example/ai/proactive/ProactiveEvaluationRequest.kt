package com.example.ai.proactive

import com.example.data.model.Message

data class ProactiveEvaluationRequest(
    val shouldEvaluate: Boolean,
    val eventType: ProactiveTriggerEvent,
    val reason: String,
    val boundedContext: String,
    val recentMessages: List<Message> = emptyList(),
    val isTopicChanged: Boolean = false
)
