package com.example.ai.provider.openrouter

import android.util.Log
import com.example.BuildConfig
import com.example.ai.SystemTimeProvider
import com.example.ai.TimeProvider
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.metadata.CapabilitySupport
import com.example.ai.provider.metadata.ProviderCapabilities
import com.example.ai.provider.openrouter.models.OpenRouterChatRequest
import com.example.ai.provider.openrouter.models.OpenRouterChatMessage
import com.example.ai.provider.openrouter.models.OpenRouterStreamChunkResponse
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderRole
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.util.PrivacyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.lang.Exception
import java.lang.StringBuilder

/**
 * OpenRouter implementation of AIProvider.
 * Leverages OpenRouter's OpenAI-compatible chat completions interface.
 * Connects to Qwen or other configured OpenRouter models securely.
 */
class OpenRouterProvider(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val apiKeyProvider: () -> String = { resolveApiKey() },
    private val modelProvider: () -> String = {
        val configured = try { BuildConfig.GROQ_MODEL } catch (e: Throwable) { "" } // Reuse model layer config if possible
        if (configured.isNotBlank() && configured.contains("/")) configured else DEFAULT_MODEL
    },
    private val apiService: OpenRouterApiService? = null
) : AIProvider {

    override val type: AIProviderType = AIProviderType.OPENROUTER
    override val providerName: String = "OpenRouter"
    override val capabilities: ProviderCapabilities = CAPABILITIES

    companion object {
        private const val TAG = "OpenRouterProvider"
        val CAPABILITIES = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.UNSUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            webSearch = CapabilitySupport.UNSUPPORTED,
            maxContextTokens = 32768
        )
        const val DEFAULT_MODEL = "qwen/qwen-2-7b-instruct:free"
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "You are Aether, an autonomous cognitive workspace assistant designed for absolute focus and zero noise. " +
            "Respond directly, concisely, and accurately without conversational filler or pleasantries unless requested."

        /**
         * Securely parses and extracts the key value from explicit configurations
         */
        fun resolveApiKey(): String {
            return com.example.util.ApiKeyStorage.getOpenRouterKey()
        }
    }

    override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
        val apiKey = apiKeyProvider().trim()

        if (apiKey.isBlank()) {
            val authErr = ProviderException.AuthenticationError(
                "OpenRouter API Key is not configured. Please add your OpenRouter key under the 'Qwen' or 'OPENROUTER_API_KEY' Secret name in AI Studio Secrets panel."
            )
            emit(
                ProviderStreamChunk(
                    textDelta = authErr.message ?: "OpenRouter API Key missing",
                    isComplete = true,
                    providerName = providerName,
                    accumulatedText = authErr.message ?: "OpenRouter API Key missing"
                )
            )
            return@flow
        }

        val openRouterRequest = buildRequest(request, stream = true)
        val clientService = apiService ?: OpenRouterRetrofitClient.service
        val authHeader = "Bearer $apiKey"

        try {
            val responseBody = clientService.generateChatCompletionStream(authHeader, openRouterRequest)
            val currentText = StringBuilder()
            var streamCompleted = false

            responseBody.byteStream().bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line?.trim() ?: continue
                    if (!rawLine.startsWith("data:")) continue
                    val jsonPayload = rawLine.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty()) continue
                    if (jsonPayload == "[DONE]") {
                        streamCompleted = true
                        break
                    }

                    try {
                        val adapter = OpenRouterRetrofitClient.moshi.adapter(OpenRouterStreamChunkResponse::class.java)
                        val chunkObj = adapter.fromJson(jsonPayload)
                        val choice = chunkObj?.choices?.firstOrNull()
                        val deltaText = choice?.delta?.content

                        if (!deltaText.isNullOrEmpty()) {
                            currentText.append(deltaText)
                            val isDone = choice.finishReason != null
                            if (isDone) streamCompleted = true

                            emit(
                                ProviderStreamChunk(
                                    textDelta = deltaText,
                                    isComplete = isDone,
                                    providerName = providerName,
                                    accumulatedText = currentText.toString()
                                )
                            )
                        } else if (choice?.finishReason != null) {
                            streamCompleted = true
                        }
                    } catch (e: Exception) {
                        val safeErr = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                        Log.e(TAG, "Error parsing OpenRouter stream chunk: $safeErr")
                    }
                }
            }

            if (currentText.isEmpty()) {
                val fallbackResponse = clientService.generateChatCompletion(
                    authHeader,
                    openRouterRequest.copy(stream = false)
                )
                val text = fallbackResponse.choices?.firstOrNull()?.message?.content
                val finalText = if (!text.isNullOrBlank()) {
                    text
                } else {
                    "Aether returned an empty response from OpenRouter."
                }
                emit(
                    ProviderStreamChunk(
                        textDelta = finalText,
                        isComplete = true,
                        providerName = providerName,
                        accumulatedText = finalText
                    )
                )
            } else if (!streamCompleted) {
                emit(
                    ProviderStreamChunk(
                        textDelta = "",
                        isComplete = true,
                        providerName = providerName,
                        accumulatedText = currentText.toString()
                    )
                )
            }
        } catch (e: Exception) {
            val mappedException = mapToProviderException(e)
            val safeErr = PrivacyUtil.sanitizeErrorMessage(mappedException.message)
            Log.e(TAG, "OpenRouter API stream failed: $safeErr")
            throw mappedException
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generate(request: ProviderRequest): ProviderResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()

        if (apiKey.isBlank()) {
            throw ProviderException.AuthenticationError("OpenRouter API Key is missing or invalid.")
        }

        val openRouterRequest = buildRequest(request, stream = false)
        val clientService = apiService ?: OpenRouterRetrofitClient.service
        val authHeader = "Bearer $apiKey"

        try {
            val response = clientService.generateChatCompletion(authHeader, openRouterRequest)
            val text = response.choices?.firstOrNull()?.message?.content
            if (!text.isNullOrBlank()) {
                ProviderResponse(text = text, providerName = providerName)
            } else {
                ProviderResponse(
                    text = "Aether returned an empty response from OpenRouter.",
                    providerName = providerName
                )
            }
        } catch (e: Exception) {
            val mappedException = mapToProviderException(e)
            val safeErr = PrivacyUtil.sanitizeErrorMessage(mappedException.message)
            Log.e(TAG, "OpenRouter API call failed: $safeErr")
            throw mappedException
        }
    }

    private fun buildRequest(request: ProviderRequest, stream: Boolean): OpenRouterChatRequest {
        val messagesList = mutableListOf<OpenRouterChatMessage>()

        // Add system instructions if present
        val systemInstructionText = request.systemInstruction ?: DEFAULT_SYSTEM_INSTRUCTION
        messagesList.add(OpenRouterChatMessage("system", systemInstructionText))

        // Add history messages
        request.history.forEach { msg ->
            val roleStr = when (msg.role) {
                ProviderRole.USER -> "user"
                ProviderRole.ASSISTANT -> "assistant"
                ProviderRole.SYSTEM -> "system"
            }
            if (msg.content.isNotBlank()) {
                messagesList.add(OpenRouterChatMessage(roleStr, msg.content))
            }
        }

        // Add user prompt if not already last
        val lastMsg = messagesList.lastOrNull()
        if (lastMsg == null || lastMsg.content != request.userMessage || lastMsg.role != "user") {
            if (request.userMessage.isNotBlank()) {
                messagesList.add(OpenRouterChatMessage("user", request.userMessage))
            }
        }

        return OpenRouterChatRequest(
            model = modelProvider(),
            messages = messagesList,
            stream = stream
        )
    }

    private fun mapToProviderException(e: Exception): ProviderException {
        val safeMessage = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage ?: e.message ?: "Unknown error")
        return when (e) {
            is ProviderException -> e
            is HttpException -> {
                when (e.code()) {
                    401, 403 -> ProviderException.AuthenticationError("OpenRouter Authentication failed ($safeMessage)", e)
                    429 -> ProviderException.RateLimitError("OpenRouter Rate limit exceeded ($safeMessage)", e)
                    in 500..599 -> ProviderException.ProviderUnavailableError("OpenRouter service unavailable ($safeMessage)", e)
                    in 400..499 -> ProviderException.InvalidRequestError("OpenRouter Invalid request ($safeMessage)", e)
                    else -> ProviderException.UnknownError("HTTP Exception ${e.code()}: $safeMessage", e)
                }
            }
            is IOException -> ProviderException.NetworkError("Network connection error: $safeMessage", e)
            else -> ProviderException.UnknownError("Unexpected provider error: $safeMessage", e)
        }
    }
}
