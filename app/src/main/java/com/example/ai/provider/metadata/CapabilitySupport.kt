package com.example.ai.provider.metadata

/**
 * Tri-state capability support status for AI providers and models.
 * Ensures unknown capabilities are explicitly represented and never falsely reported as supported.
 */
enum class CapabilitySupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN;

    val isSupported: Boolean get() = this == SUPPORTED
}
