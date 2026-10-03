package com.example.ai.provider.routing

import com.example.ai.provider.AIProviderType

/**
 * Provider-neutral task requirements profile for routing evaluation.
 * Captures explicit structured task demands without inferring from raw text.
 */
data class RoutingTaskProfile(
    val requiresLongContext: Boolean = false,
    val requiresToolCalling: Boolean = false,
    val requiresVision: Boolean = false,
    val requiresWebSearch: Boolean = false,
    val estimatedInputTokens: Int? = null,
    val userPreferredProvider: AIProviderType? = null,
    val inferredIntent: com.example.ai.intent.TaskIntentResult? = null
)
