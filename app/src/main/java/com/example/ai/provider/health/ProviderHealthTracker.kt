package com.example.ai.provider.health

import com.example.ai.provider.AIProviderType
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, thread-safe provider health and reliability tracker.
 * Does not perform network checks; records execution outcomes reported by provider operations.
 * Strictly in-memory with zero persistence and no secret handling.
 */
class ProviderHealthTracker(
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD
) {
    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
    }

    private val healthMap = ConcurrentHashMap<AIProviderType, ProviderHealthInfo>()

    /**
     * Records a successful execution outcome for the specified provider.
     * Resets consecutive failures to 0 and transitions the health state to HEALTHY.
     */
    fun recordSuccess(provider: AIProviderType, timestamp: Long = System.currentTimeMillis()) {
        healthMap.compute(provider) { _, current ->
            ProviderHealthInfo(
                providerType = provider,
                healthState = ProviderHealthState.HEALTHY,
                consecutiveFailures = 0,
                lastFailureTimestamp = current?.lastFailureTimestamp,
                lastSuccessTimestamp = timestamp
            )
        }
    }

    /**
     * Records a failure execution outcome for the specified provider.
     * Increments consecutive failures and transitions to DEGRADED once failureThreshold is reached.
     * Does not mark the provider permanently UNAVAILABLE.
     */
    fun recordFailure(provider: AIProviderType, timestamp: Long = System.currentTimeMillis()) {
        healthMap.compute(provider) { _, current ->
            val prevFailures = current?.consecutiveFailures ?: 0
            val newFailures = prevFailures + 1
            val newState = if (newFailures >= failureThreshold) {
                ProviderHealthState.DEGRADED
            } else {
                current?.healthState ?: ProviderHealthState.UNKNOWN
            }

            ProviderHealthInfo(
                providerType = provider,
                healthState = newState,
                consecutiveFailures = newFailures,
                lastFailureTimestamp = timestamp,
                lastSuccessTimestamp = current?.lastSuccessTimestamp
            )
        }
    }

    /**
     * Retrieves current health information for a provider.
     * Returns UNKNOWN state if no outcomes have been recorded yet.
     */
    fun getHealth(provider: AIProviderType): ProviderHealthInfo {
        return healthMap.getOrDefault(
            provider,
            ProviderHealthInfo(
                providerType = provider,
                healthState = ProviderHealthState.UNKNOWN,
                consecutiveFailures = 0,
                lastFailureTimestamp = null,
                lastSuccessTimestamp = null
            )
        )
    }

    /**
     * Resets health state for a single provider back to UNKNOWN.
     */
    fun reset(provider: AIProviderType) {
        healthMap.remove(provider)
    }

    /**
     * Clears all in-memory health tracking.
     */
    fun resetAll() {
        healthMap.clear()
    }
}
