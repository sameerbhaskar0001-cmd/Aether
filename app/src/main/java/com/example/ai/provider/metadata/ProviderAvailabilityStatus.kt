package com.example.ai.provider.metadata

/**
 * Standardized status abstraction distinguishing configuration and availability states.
 * Keeps status completely separate from API keys or secret credentials.
 */
enum class ProviderAvailabilityStatus {
    CONFIGURED,
    NOT_CONFIGURED,
    AVAILABLE,
    UNAVAILABLE
}

/**
 * Rich status information for a provider.
 */
data class ProviderStatusInfo(
    val availabilityStatus: ProviderAvailabilityStatus,
    val configurationStatus: ProviderAvailabilityStatus,
    val isConfigured: Boolean,
    val isAvailable: Boolean,
    val isEligibleForAuto: Boolean,
    val description: String? = null
)
