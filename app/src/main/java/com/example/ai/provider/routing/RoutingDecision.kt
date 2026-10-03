package com.example.ai.provider.routing

import com.example.ai.provider.AIProviderType

/**
 * Result of the provider-neutral auto-routing evaluation.
 */
data class RoutingDecision(
    val selectedProvider: AIProviderType?,
    val candidates: List<RoutingCandidate>,
    val reason: String,
    val isFallbackRequired: Boolean = false
)
