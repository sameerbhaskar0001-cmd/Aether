package com.example.ai.provider.performance

import com.example.ai.provider.AIProviderType

/**
 * Snapshot of performance metrics for an AI provider.
 * Thread-safe immutable data class.
 */
data class ProviderPerformanceInfo(
    val providerType: AIProviderType,
    val lastRequestLatencyMs: Long? = null,
    val averageLatencyMs: Long? = null,
    val successfulRequestCount: Int = 0,
    val failedRequestCount: Int = 0
)
