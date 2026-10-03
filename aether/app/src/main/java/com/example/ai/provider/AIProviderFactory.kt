package com.example.ai.provider

import com.example.ai.SystemTimeProvider
import com.example.ai.TimeProvider
import com.example.ai.provider.groq.GroqProvider

/**
 * Factory responsible for instantiating and selecting AIProvider instances.
 */
class AIProviderFactory(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val providerMap: Map<AIProviderType, AIProvider>? = null,
    private val defaultProviderType: AIProviderType = AIProviderType.GEMINI
) {
    /**
     * Retrieves an AIProvider implementation matching the requested provider type.
     */
    fun getProvider(type: AIProviderType = defaultProviderType): AIProvider {
        providerMap?.get(type)?.let { return it }

        return when (type) {
            AIProviderType.GEMINI -> GeminiProvider(timeProvider = timeProvider)
            AIProviderType.GROQ -> GroqProvider(timeProvider = timeProvider)
        }
    }
}
