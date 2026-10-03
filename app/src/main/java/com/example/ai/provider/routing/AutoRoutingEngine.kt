package com.example.ai.provider.routing

import com.example.ai.provider.AIProviderType
import com.example.ai.provider.cost.CostQuotaRegistry
import com.example.ai.provider.health.ProviderHealthState
import com.example.ai.provider.health.ProviderHealthTracker
import com.example.ai.provider.metadata.AIProviderMetadataRegistry
import com.example.ai.provider.metadata.CapabilitySupport
import com.example.ai.provider.performance.ProviderPerformanceTracker
import kotlin.math.max

/**
 * Provider-neutral auto-routing decision engine.
 * Evaluates active and configured providers using structured signals (capabilities, health, performance, cost).
 * Purely in-memory, deterministic, and bounded.
 */
class AutoRoutingEngine(
    private val metadataRegistry: AIProviderMetadataRegistry,
    private val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    private val performanceTracker: ProviderPerformanceTracker = ProviderPerformanceTracker(),
    private val costQuotaRegistry: CostQuotaRegistry = CostQuotaRegistry()
) {

    /**
     * Evaluates all known provider candidates against the given task profile.
     */
    fun evaluate(taskProfile: RoutingTaskProfile): RoutingDecision {
        val candidates = AIProviderType.values().map { providerType ->
            evaluateCandidate(providerType, taskProfile)
        }

        val eligibleCandidates = candidates.filter { it.eligible }
            .sortedWith(
                compareByDescending<RoutingCandidate> { it.score }
                    .thenBy { it.providerType.ordinal }
            )

        val selected = eligibleCandidates.firstOrNull()

        return if (selected != null) {
            RoutingDecision(
                selectedProvider = selected.providerType,
                candidates = candidates,
                reason = "Selected ${selected.providerType} (score: ${"%.2f".format(selected.score)}) based on capability, health, and performance fit.",
                isFallbackRequired = false
            )
        } else {
            val failureSummary = candidates.joinToString("; ") { "${it.providerType}: ${it.exclusionReason ?: "Ineligible"}" }
            RoutingDecision(
                selectedProvider = null,
                candidates = candidates,
                reason = "No eligible providers available for this task profile ($failureSummary).",
                isFallbackRequired = true
            )
        }
    }

    private fun evaluateCandidate(
        providerType: AIProviderType,
        taskProfile: RoutingTaskProfile
    ): RoutingCandidate {
        val metadata = metadataRegistry.getProviderMetadata(providerType)

        // 1. Must be active in release
        if (metadata == null || !metadata.isActive) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Provider is inactive in this release"
            )
        }

        // 2. Must be configured and available
        if (!metadataRegistry.isAvailable(providerType)) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Provider API credentials are not configured"
            )
        }

        val capabilities = metadata.capabilities

        // 3. Required capabilities must be explicitly SUPPORTED (UNKNOWN or UNSUPPORTED are rejected)
        if (taskProfile.requiresToolCalling && capabilities.toolCalling != CapabilitySupport.SUPPORTED) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Tool calling required but not supported"
            )
        }

        if (taskProfile.requiresVision && capabilities.vision != CapabilitySupport.SUPPORTED) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Vision required but not supported or unknown"
            )
        }

        if (taskProfile.requiresWebSearch && capabilities.webSearch != CapabilitySupport.SUPPORTED) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Web search required but not supported"
            )
        }

        // 4. Context limits check
        if (taskProfile.estimatedInputTokens != null && capabilities.maxContextTokens != null) {
            if (capabilities.maxContextTokens < taskProfile.estimatedInputTokens) {
                return RoutingCandidate(
                    providerType = providerType,
                    eligible = false,
                    exclusionReason = "Input tokens (${taskProfile.estimatedInputTokens}) exceeds context limit (${capabilities.maxContextTokens})"
                )
            }
        }

        if (taskProfile.requiresLongContext && (capabilities.maxContextTokens == null || capabilities.maxContextTokens < 32768)) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Long context required but provider context limit is insufficient"
            )
        }

        // 5. Check health state
        val health = healthTracker.getHealth(providerType)
        if (health.healthState == ProviderHealthState.UNAVAILABLE) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Provider health state is UNAVAILABLE"
            )
        }

        // 6. Check quota exhaustion
        val costQuota = costQuotaRegistry.getCostQuota(providerType)
        if (costQuota.quotaKnown && costQuota.quotaRemaining != null && costQuota.quotaRemaining <= 0.0) {
            return RoutingCandidate(
                providerType = providerType,
                eligible = false,
                exclusionReason = "Provider quota is exhausted"
            )
        }

        // Calculate scoring components (each strictly normalized 0.0 to 1.0)
        // A. Capability fit
        var capFit = 0.8
        if (taskProfile.userPreferredProvider == providerType) {
            capFit += 0.2
        }
        if (taskProfile.requiresLongContext && capabilities.maxContextTokens != null && capabilities.maxContextTokens >= 100000) {
            capFit += 0.1
        }
        capFit = capFit.coerceIn(0.0, 1.0)

        // B. Health fit
        var hlthFit = when (health.healthState) {
            ProviderHealthState.HEALTHY -> 1.0
            ProviderHealthState.UNKNOWN -> 0.8
            ProviderHealthState.DEGRADED -> 0.3
            ProviderHealthState.UNAVAILABLE -> 0.0
        }
        if (health.consecutiveFailures > 0) {
            hlthFit = max(0.1, hlthFit - (health.consecutiveFailures * 0.15))
        }
        hlthFit = hlthFit.coerceIn(0.0, 1.0)

        // C. Performance fit
        val perf = performanceTracker.getPerformance(providerType)
        val perfFit = if (perf.averageLatencyMs != null) {
            // 100ms -> 1.0, 2000ms -> 0.1
            val clampedLatency = perf.averageLatencyMs.coerceIn(100L, 2000L).toDouble()
            val normalized = (2000.0 - clampedLatency) / 1900.0 * 0.9 + 0.1
            normalized.coerceIn(0.1, 1.0)
        } else {
            0.5 // neutral when unmeasured
        }

        // D. Cost/Quota fit
        val costFit = if (costQuota.costKnown && costQuota.estimatedCost != null) {
            val cost = costQuota.estimatedCost.coerceAtLeast(0.0)
            (1.0 / (1.0 + cost * 1000.0)).coerceIn(0.1, 1.0)
        } else {
            0.5 // neutral when unknown
        }

        // Weighted composite score (0.0 to 1.0)
        val finalScore = (capFit * 0.35) + (hlthFit * 0.35) + (perfFit * 0.15) + (costFit * 0.15)

        return RoutingCandidate(
            providerType = providerType,
            eligible = true,
            exclusionReason = null,
            score = finalScore,
            capabilityFit = capFit,
            healthFit = hlthFit,
            performanceFit = perfFit,
            costFit = costFit
        )
    }
}
