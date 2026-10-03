package com.example.ai.provider.cost

import com.example.ai.provider.AIProviderType

/**
 * Provider-neutral cost and quota telemetry model for future intelligent routing signals.
 * Does not make billing API calls or invent pricing.
 * Unknown values explicitly remain unknown/null.
 */
data class CostQuotaInfo(
    val providerType: AIProviderType,
    val costKnown: Boolean = false,
    val estimatedCost: Double? = null,
    val quotaKnown: Boolean = false,
    val quotaRemaining: Double? = null
)
