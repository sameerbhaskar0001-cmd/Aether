package com.example.ai.provider.models

import com.example.data.model.Memory

/**
 * Domain-level representation of roles in AI provider requests/responses.
 */
enum class ProviderRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Domain-level representation of a single message in a provider conversation context.
 */
data class ProviderMessage(
    val role: ProviderRole,
    val content: String
)

/**
 * Domain-level request model sent to an AIProvider.
 */
data class ProviderRequest(
    val userMessage: String,
    val history: List<ProviderMessage> = emptyList(),
    val relevantMemories: List<com.example.data.model.Memory> = emptyList(),
    val systemInstruction: String? = null,
    val languageHint: String? = null,
    val conversationContext: com.example.ai.context.ConversationContext? = null,
    val fileContext: String? = null
)

/**
 * Domain-level streaming chunk emitted by an AIProvider.
 */
data class ProviderStreamChunk(
    val textDelta: String,
    val isComplete: Boolean = false,
    val providerName: String,
    val accumulatedText: String = textDelta
)

/**
 * Domain-level complete response model returned by an AIProvider.
 */
data class ProviderResponse(
    val text: String,
    val providerName: String
)

/**
 * Provider-neutral error categories for standardized error handling across providers.
 */
enum class ProviderErrorCategory {
    NETWORK_ERROR,
    AUTHENTICATION_ERROR,
    RATE_LIMIT_ERROR,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    UNKNOWN_ERROR
}

/**
 * Provider-neutral base exception class protecting domain layers from provider-specific SDK/HTTP details.
 */
sealed class ProviderException(
    val category: ProviderErrorCategory,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    class NetworkError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.NETWORK_ERROR, message, cause)

    class AuthenticationError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.AUTHENTICATION_ERROR, message, cause)

    class RateLimitError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.RATE_LIMIT_ERROR, message, cause)

    class ProviderUnavailableError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.PROVIDER_UNAVAILABLE, message, cause)

    class InvalidRequestError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.INVALID_REQUEST, message, cause)

    class UnknownError(message: String, cause: Throwable? = null) : 
        ProviderException(ProviderErrorCategory.UNKNOWN_ERROR, message, cause)
}
