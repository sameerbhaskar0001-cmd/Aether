package com.example.ai.proactive

import com.example.data.model.Memory

enum class ProactiveAction {
    PROACT,
    STAY_SILENT
}

data class ProactiveDecision(
    val decision: ProactiveAction,
    val confidence: Double,
    val reason: String,
    val category: String,
    val supportingMemory: Memory? = null,
    val priority: Int = 0,
    val cooldownExpiry: Long = 0L
)
