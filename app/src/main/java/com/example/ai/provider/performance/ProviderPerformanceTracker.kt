package com.example.ai.provider.performance

import com.example.ai.provider.AIProviderType
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, thread-safe provider performance tracker.
 * Computes deterministic, bounded rolling average latency and request counts.
 * Strictly in-memory with zero persistence and no secret handling.
 */
class ProviderPerformanceTracker(
    val windowSize: Int = DEFAULT_WINDOW_SIZE
) {
    companion object {
        const val DEFAULT_WINDOW_SIZE = 10
    }

    private class ProviderPerformanceInternal(
        val windowSize: Int
    ) {
        var lastRequestLatencyMs: Long? = null
        var successfulRequestCount: Int = 0
        var failedRequestCount: Int = 0
        val recentLatencies = ArrayDeque<Long>()

        val averageLatencyMs: Long?
            get() = if (recentLatencies.isEmpty()) null else recentLatencies.sum() / recentLatencies.size

        fun toInfo(providerType: AIProviderType): ProviderPerformanceInfo {
            return ProviderPerformanceInfo(
                providerType = providerType,
                lastRequestLatencyMs = lastRequestLatencyMs,
                averageLatencyMs = averageLatencyMs,
                successfulRequestCount = successfulRequestCount,
                failedRequestCount = failedRequestCount
            )
        }
    }

    private val performanceMap = ConcurrentHashMap<AIProviderType, ProviderPerformanceInternal>()

    /**
     * Records a successful request latency for the given provider.
     * Negative latencies are safely ignored.
     */
    @Synchronized
    fun recordRequestSuccess(provider: AIProviderType, latencyMs: Long) {
        if (latencyMs < 0) return
        val internal = performanceMap.computeIfAbsent(provider) { ProviderPerformanceInternal(windowSize) }
        internal.successfulRequestCount++
        internal.lastRequestLatencyMs = latencyMs
        internal.recentLatencies.addLast(latencyMs)
        if (internal.recentLatencies.size > windowSize) {
            internal.recentLatencies.removeFirst()
        }
    }

    /**
     * Records a failed request for the given provider, optionally recording latency if measured.
     * Negative latencies are safely ignored.
     */
    @Synchronized
    fun recordRequestFailure(provider: AIProviderType, latencyMs: Long? = null) {
        if (latencyMs != null && latencyMs < 0) return
        val internal = performanceMap.computeIfAbsent(provider) { ProviderPerformanceInternal(windowSize) }
        internal.failedRequestCount++
        if (latencyMs != null) {
            internal.lastRequestLatencyMs = latencyMs
        }
    }

    /**
     * Returns a snapshot of the current performance telemetry for a provider.
     */
    @Synchronized
    fun getPerformance(provider: AIProviderType): ProviderPerformanceInfo {
        return performanceMap[provider]?.toInfo(provider) ?: ProviderPerformanceInfo(
            providerType = provider,
            lastRequestLatencyMs = null,
            averageLatencyMs = null,
            successfulRequestCount = 0,
            failedRequestCount = 0
        )
    }

    /**
     * Clears tracked performance telemetry for a single provider.
     */
    @Synchronized
    fun reset(provider: AIProviderType) {
        performanceMap.remove(provider)
    }

    /**
     * Clears all tracked performance telemetry across all providers.
     */
    @Synchronized
    fun resetAll() {
        performanceMap.clear()
    }
}
