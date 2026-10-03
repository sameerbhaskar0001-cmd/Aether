package com.example.ai.provider.metadata

/**
 * Provider-neutral capability metadata representing feature support for a provider or model.
 */
data class ProviderCapabilities(
    val streaming: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val toolCalling: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val vision: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val webSearch: CapabilitySupport = CapabilitySupport.UNKNOWN,
    val maxContextTokens: Int? = null
) {
    /**
     * Safely queries whether a specific capability is supported without false positives for UNKNOWN.
     */
    fun isSupported(capabilitySelector: (ProviderCapabilities) -> CapabilitySupport): Boolean {
        return capabilitySelector(this) == CapabilitySupport.SUPPORTED
    }
}
