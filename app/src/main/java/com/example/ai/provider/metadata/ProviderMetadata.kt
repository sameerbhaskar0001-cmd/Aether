package com.example.ai.provider.metadata

import com.example.ai.provider.AIProviderType

/**
 * Provider metadata definition holding capabilities, supported models, and active lifecycle state.
 */
data class ProviderMetadata(
    val type: AIProviderType,
    val displayName: String,
    val capabilities: ProviderCapabilities,
    val models: List<ModelMetadata>,
    val defaultModelId: String,
    val isActive: Boolean = true
)
