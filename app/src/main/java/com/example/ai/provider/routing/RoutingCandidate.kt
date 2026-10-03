package com.example.ai.provider.routing

import com.example.ai.provider.AIProviderType

/**
 * Candidate provider evaluation breakdown for an auto-routing decision.
 * All scoring values are deterministic, bounded between 0.0 and 1.0, and explainable.
 */
data class RoutingCandidate(
    val providerType: AIProviderType,
    val eligible: Boolean,
    val exclusionReason: String? = null,
    val score: Double = 0.0,
    val capabilityFit: Double = 0.0,
    val healthFit: Double = 0.0,
    val performanceFit: Double = 0.0,
    val costFit: Double = 0.0
)
