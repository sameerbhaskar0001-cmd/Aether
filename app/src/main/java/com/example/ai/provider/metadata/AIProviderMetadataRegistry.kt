package com.example.ai.provider.metadata

import com.example.ai.provider.AIProviderType
import com.example.ai.provider.groq.GroqProvider
import com.example.ai.provider.openrouter.OpenRouterProvider

/**
 * Clean, provider-neutral capability and availability registry.
 * Operates above individual providers without altering execution architecture.
 * In-memory only, strictly preserving Incognito isolation and secret safety.
 */
class AIProviderMetadataRegistry(
    private val isKeyConfigured: (AIProviderType) -> Boolean = { defaultKeyCheck(it) }
) {
    private val providerDescriptors = mutableMapOf<AIProviderType, ProviderMetadata>()

    init {
        registerDefaultProviders()
    }

    private fun registerDefaultProviders() {
        // 1. Gemini
        val geminiCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.SUPPORTED,
            vision = CapabilitySupport.UNKNOWN,
            webSearch = CapabilitySupport.SUPPORTED,
            maxContextTokens = 1048576
        )
        val geminiDefaultModel = ModelMetadata(
            modelId = "gemini-3.5-flash",
            displayName = "Gemini 3.5 Flash",
            providerType = AIProviderType.GEMINI,
            capabilities = geminiCapabilities,
            maxContextTokens = 1048576,
            isDefault = true
        )
        registerProvider(
            ProviderMetadata(
                type = AIProviderType.GEMINI,
                displayName = "Gemini",
                capabilities = geminiCapabilities,
                models = listOf(geminiDefaultModel),
                defaultModelId = geminiDefaultModel.modelId,
                isActive = true
            )
        )

        // 2. Groq
        val groqCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.UNSUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            webSearch = CapabilitySupport.UNSUPPORTED,
            maxContextTokens = 8192
        )
        val groqDefaultModel = ModelMetadata(
            modelId = GroqProvider.DEFAULT_MODEL,
            displayName = "GPT OSS 20B",
            providerType = AIProviderType.GROQ,
            capabilities = groqCapabilities,
            maxContextTokens = 8192,
            isDefault = true
        )
        registerProvider(
            ProviderMetadata(
                type = AIProviderType.GROQ,
                displayName = "Groq",
                capabilities = groqCapabilities,
                models = listOf(groqDefaultModel),
                defaultModelId = groqDefaultModel.modelId,
                isActive = true
            )
        )

        // 3. OpenRouter
        val openRouterCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.UNSUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            webSearch = CapabilitySupport.UNSUPPORTED,
            maxContextTokens = 32768
        )
        val openRouterDefaultModel = ModelMetadata(
            modelId = OpenRouterProvider.DEFAULT_MODEL,
            displayName = "Qwen 2 7B Instruct (Free)",
            providerType = AIProviderType.OPENROUTER,
            capabilities = openRouterCapabilities,
            maxContextTokens = 32768,
            isDefault = true
        )
        registerProvider(
            ProviderMetadata(
                type = AIProviderType.OPENROUTER,
                displayName = "OpenRouter",
                capabilities = openRouterCapabilities,
                models = listOf(openRouterDefaultModel),
                defaultModelId = openRouterDefaultModel.modelId,
                isActive = true
            )
        )

        // 4. OpenAI (Inactive / Future)
        val openAiCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.UNKNOWN,
            toolCalling = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            webSearch = CapabilitySupport.UNKNOWN,
            maxContextTokens = null
        )
        registerProvider(
            ProviderMetadata(
                type = AIProviderType.OPENAI,
                displayName = "OpenAI",
                capabilities = openAiCapabilities,
                models = emptyList(),
                defaultModelId = "",
                isActive = false
            )
        )

        // 5. Claude (Inactive / Future)
        val claudeCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.UNKNOWN,
            toolCalling = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            webSearch = CapabilitySupport.UNKNOWN,
            maxContextTokens = null
        )
        registerProvider(
            ProviderMetadata(
                type = AIProviderType.CLAUDE,
                displayName = "Claude",
                capabilities = claudeCapabilities,
                models = emptyList(),
                defaultModelId = "",
                isActive = false
            )
        )
    }

    fun registerProvider(metadata: ProviderMetadata) {
        providerDescriptors[metadata.type] = metadata
    }

    fun getProviderMetadata(type: AIProviderType): ProviderMetadata? {
        return providerDescriptors[type]
    }

    fun getAllProviders(): List<ProviderMetadata> {
        return providerDescriptors.values.toList()
    }

    fun getActiveProviders(): List<ProviderMetadata> {
        return providerDescriptors.values.filter { it.isActive }
    }

    fun isConfigured(type: AIProviderType): Boolean {
        val metadata = providerDescriptors[type] ?: return false
        if (!metadata.isActive) return false
        return isKeyConfigured(type)
    }

    fun isAvailable(type: AIProviderType): Boolean {
        val metadata = providerDescriptors[type] ?: return false
        return metadata.isActive && isConfigured(type)
    }

    fun isEligibleForAuto(type: AIProviderType): Boolean {
        return isAvailable(type)
    }

    fun getConfigurationStatus(type: AIProviderType): ProviderAvailabilityStatus {
        return if (isConfigured(type)) {
            ProviderAvailabilityStatus.CONFIGURED
        } else {
            ProviderAvailabilityStatus.NOT_CONFIGURED
        }
    }

    fun getAvailabilityStatus(type: AIProviderType): ProviderAvailabilityStatus {
        return if (isAvailable(type)) {
            ProviderAvailabilityStatus.AVAILABLE
        } else {
            ProviderAvailabilityStatus.UNAVAILABLE
        }
    }

    fun getStatusInfo(type: AIProviderType): ProviderStatusInfo {
        val metadata = providerDescriptors[type]
        if (metadata == null || !metadata.isActive) {
            return ProviderStatusInfo(
                availabilityStatus = ProviderAvailabilityStatus.UNAVAILABLE,
                configurationStatus = ProviderAvailabilityStatus.NOT_CONFIGURED,
                isConfigured = false,
                isAvailable = false,
                isEligibleForAuto = false,
                description = if (metadata == null) "Unknown provider" else "Provider is inactive in this release"
            )
        }

        val configured = isConfigured(type)
        return ProviderStatusInfo(
            availabilityStatus = if (configured) ProviderAvailabilityStatus.AVAILABLE else ProviderAvailabilityStatus.UNAVAILABLE,
            configurationStatus = if (configured) ProviderAvailabilityStatus.CONFIGURED else ProviderAvailabilityStatus.NOT_CONFIGURED,
            isConfigured = configured,
            isAvailable = configured,
            isEligibleForAuto = configured,
            description = if (configured) "Configured and available" else "API key is not configured"
        )
    }

    fun getCapabilities(type: AIProviderType): ProviderCapabilities? {
        return providerDescriptors[type]?.capabilities
    }

    fun getModels(type: AIProviderType): List<ModelMetadata> {
        return providerDescriptors[type]?.models ?: emptyList()
    }

    fun getDefaultModel(type: AIProviderType): ModelMetadata? {
        val metadata = providerDescriptors[type] ?: return null
        return metadata.models.find { it.modelId == metadata.defaultModelId } ?: metadata.models.firstOrNull()
    }

    /**
     * Resolves the active model for a provider, honoring dynamic model configuration (such as custom OpenRouter models)
     * while preserving model and provider separation.
     */
    fun getActiveModel(type: AIProviderType): ModelMetadata? {
        val provider = getProviderMetadata(type) ?: return null
        if (type == AIProviderType.OPENROUTER) {
            val configured = System.getenv("OPENROUTER_MODEL")
            if (!configured.isNullOrBlank() && configured.contains("/")) {
                return ModelMetadata(
                    modelId = configured.trim(),
                    displayName = configured.trim().substringAfter("/"),
                    providerType = AIProviderType.OPENROUTER,
                    capabilities = provider.capabilities,
                    maxContextTokens = 32768,
                    isDefault = false
                )
            }
        }
        return getDefaultModel(type)
    }

    companion object {
        fun defaultKeyCheck(type: AIProviderType): Boolean {
            return com.example.util.ApiKeyStorage.isProviderConfigured(type)
        }
    }
}
