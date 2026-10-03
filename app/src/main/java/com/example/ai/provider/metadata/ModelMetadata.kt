package com.example.ai.provider.metadata

import com.example.ai.provider.AIProviderType

/**
 * Model-level metadata keeping model separate from provider.
 */
data class ModelMetadata(
    val modelId: String,
    val displayName: String,
    val providerType: AIProviderType,
    val capabilities: ProviderCapabilities,
    val maxContextTokens: Int? = null,
    val isDefault: Boolean = false
)
