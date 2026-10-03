package com.example.ai.provider.health

import com.example.ai.provider.AIProviderType

/**
 * Snapshot of health and reliability telemetry for an AI provider.
 * Thread-safe immutable data class.
 */
data class ProviderHealthInfo(
    val providerType: AIProviderType,
    val healthState: ProviderHealthState,
    val consecutiveFailures: Int = 0,
    val lastFailureTimestamp: Long? = null,
    val lastSuccessTimestamp: Long? = null
)
