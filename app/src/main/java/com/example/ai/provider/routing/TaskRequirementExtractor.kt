package com.example.ai.provider.routing

import com.example.ai.provider.models.ProviderRequest

/**
 * Provider-neutral abstraction for extracting structured task requirements from a request.
 * Pure and deterministic: no network calls, no provider calls, no persistence, no side effects.
 */
interface TaskRequirementExtractor {
    /**
     * Extracts a RoutingTaskProfile from the given ProviderRequest.
     * Guarantees deterministic, pure extraction based solely on structured inputs.
     */
    fun extract(request: ProviderRequest): RoutingTaskProfile
}
