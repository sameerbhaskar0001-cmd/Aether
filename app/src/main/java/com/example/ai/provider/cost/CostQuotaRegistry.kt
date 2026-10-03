package com.example.ai.provider.cost

import com.example.ai.provider.AIProviderType
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry for provider cost and quota metadata.
 * Does not contain fabricated pricing or quota values.
 * Unknown by default.
 */
class CostQuotaRegistry {
    private val costQuotaMap = ConcurrentHashMap<AIProviderType, CostQuotaInfo>()

    /**
     * Stores explicit cost/quota metadata for a provider.
     */
    fun setCostQuota(info: CostQuotaInfo) {
        costQuotaMap[info.providerType] = info
    }

    /**
     * Retrieves cost and quota metadata for a provider.
     * Returns unknown values if no explicit metadata has been supplied.
     */
    fun getCostQuota(provider: AIProviderType): CostQuotaInfo {
        return costQuotaMap.getOrDefault(
            provider,
            CostQuotaInfo(
                providerType = provider,
                costKnown = false,
                estimatedCost = null,
                quotaKnown = false,
                quotaRemaining = null
            )
        )
    }

    /**
     * Resets cost and quota metadata for a provider.
     */
    fun reset(provider: AIProviderType) {
        costQuotaMap.remove(provider)
    }

    /**
     * Clears all cost and quota metadata across all providers.
     */
    fun resetAll() {
        costQuotaMap.clear()
    }
}
