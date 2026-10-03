package com.example.ai.proactive

import com.example.data.model.Memory

enum class ProactiveSignalType {
    GOAL_RELEVANT,
    PROJECT_RELEVANT,
    UPCOMING_EVENT,
    UNFINISHED_ITEM,
    REPEATED_CONCERN,
    FUTURE_INTENTION,
    IMPORTANT_DECISION
}

data class ProactiveSignal(
    val id: String,
    val type: ProactiveSignalType,
    val title: String,
    val sourceMemoryId: String?,
    val relevanceContext: String?,
    val confidence: Double,
    val priority: Int,
    val timestamp: Long,
    val expirationTimestamp: Long? = null,
    val isUnfinished: Boolean = false,
    val isTimeSensitive: Boolean = false,
    val explanation: String
)
