package com.example.ai.provider

import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderStreamChunk
import kotlinx.coroutines.flow.Flow

/**
 * Common AI provider contract decoupling Aether's core assistant architecture
 * from specific model providers.
 */
interface AIProvider {
    /**
     * Unique identifier for the provider implementation.
     */
    val type: AIProviderType

    /**
     * Display name for the provider.
     */
    val providerName: String

    /**
     * Generates a streaming text response flow for the given domain request.
     */
    fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk>

    /**
     * Generates a complete non-streaming response for the given domain request.
     */
    suspend fun generate(request: ProviderRequest): ProviderResponse
}
