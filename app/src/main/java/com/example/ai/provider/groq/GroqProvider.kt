package com.example.ai.provider.groq

import android.util.Log
import com.example.BuildConfig
import com.example.ai.SystemTimeProvider
import com.example.ai.TimeProvider
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.metadata.CapabilitySupport
import com.example.ai.provider.metadata.ProviderCapabilities
import com.example.ai.provider.groq.models.GroqChatCompletionRequest
import com.example.ai.provider.groq.models.GroqChatMessage
import com.example.ai.provider.groq.models.GroqStreamChunkResponse
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
 * Groq implementation of AIProvider utilizing Groq's OpenAI-compatible chat completions API.
 */
class GroqProvider(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val apiKeyProvider: () -> String = { com.example.util.ApiKeyStorage.getGroqKey() },
    private val modelProvider: () -> String = { 
        val configured = try { BuildConfig.GROQ_MODEL } catch (e: Throwable) { "" }
        if (configured.isNotBlank() && configured != "GROQ_MODEL") configured else DEFAULT_MODEL
    },
    private val apiService: GroqApiService? = null
) : AIProvider {

    override val type: AIProviderType = AIProviderType.GROQ
    override val providerName: String = "GROQ"
    override val capabilities: ProviderCapabilities = CAPABILITIES

    companion object {
        private const val TAG = "GroqProvider"
        val CAPABILITIES = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.UNSUPPORTED,
            vision = CapabilitySupport.UNSUPPORTED,
            webSearch = CapabilitySupport.UNSUPPORTED,
            maxContextTokens = 8192
        )
        const val DEFAULT_MODEL = "openai/gpt-oss-20b"
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "You are Aether, an autonomous cognitive workspace assistant designed for absolute focus and zero noise. " +
            "Respond directly, concisely, and accurately without conversational filler or pleasantries unless requested."
    }

    override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
        val apiKey = apiKeyProvider().trim()

        if (apiKey.isBlank() || apiKey == "MY_GROQ_API_KEY") {
            val authErr = ProviderException.AuthenticationError(
                "Groq API Key is not configured. Please add your Groq API Key in the Secrets panel in Google AI Studio to unlock Groq intelligence."
            )
            emit(
                ProviderStreamChunk(
                    textDelta = authErr.message ?: "Groq API Key missing",
                    isComplete = true,
                    providerName = providerName,
                    accumulatedText = authErr.message ?: "Groq API Key missing"
                )
            )
            return@flow
        }

        val groqRequest = buildGroqRequest(request, stream = true)
        val clientService = apiService ?: GroqRetrofitClient.service
        val authHeader = "Bearer $apiKey"

        var chunkCount = 0
        var contentChunkCount = 0

        Log.d(TAG, "Groq stream start: provider=GROQ, configured=true, model=${groqRequest.model}")

        try {
            val responseBody = clientService.generateChatCompletionStream(authHeader, groqRequest)
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
                        val adapter = GroqRetrofitClient.moshi.adapter(GroqStreamChunkResponse::class.java)
                        val chunkObj = adapter.fromJson(jsonPayload)
                        val choice = chunkObj?.choices?.firstOrNull()
                        val deltaText = choice?.delta?.content

                        chunkCount++

                        if (!deltaText.isNullOrEmpty()) {
                            contentChunkCount++
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
                        Log.e(TAG, "Error parsing Groq stream chunk: $safeErr")
                    }
                }
            }

            Log.d(TAG, "Groq stream end: chunkCount=$chunkCount, contentChunkCount=$contentChunkCount, accumulatedTextLength=${currentText.length}, streamCompleted=$streamCompleted")

            if (currentText.isEmpty()) {
                // If stream was empty, fallback to non-streaming call
                val fallbackResponse = clientService.generateChatCompletion(
                    authHeader,
                    groqRequest.copy(stream = false)
                )
                val text = fallbackResponse.choices?.firstOrNull()?.message?.content
                val finalText = if (!text.isNullOrBlank()) {
                    text
                } else {
                    "Aether returned an empty response from Groq. Please try reframing your instruction."
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
            Log.e(TAG, "Groq API stream failed: $safeErr")
            throw mappedException
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generate(request: ProviderRequest): ProviderResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()

        if (apiKey.isBlank() || apiKey == "MY_GROQ_API_KEY") {
            throw ProviderException.AuthenticationError(
                "Groq API Key is not configured. Please add your Groq API Key in the Secrets panel in Google AI Studio."
            )
        }

        val groqRequest = buildGroqRequest(request, stream = false)
        val clientService = apiService ?: GroqRetrofitClient.service
        val authHeader = "Bearer $apiKey"

        try {
            val response = clientService.generateChatCompletion(authHeader, groqRequest)
            val text = response.choices?.firstOrNull()?.message?.content
            if (!text.isNullOrBlank()) {
                ProviderResponse(text = text, providerName = providerName)
            } else {
                ProviderResponse(
                    text = "Aether returned an empty response from Groq. Please try reframing your instruction.",
                    providerName = providerName
                )
            }
        } catch (e: Exception) {
            val mappedException = mapToProviderException(e)
            val safeErr = PrivacyUtil.sanitizeErrorMessage(mappedException.message)
            Log.e(TAG, "Groq API non-streaming call failed: $safeErr")
            throw mappedException
        }
    }

    internal fun buildGroqRequest(request: ProviderRequest, stream: Boolean): GroqChatCompletionRequest {
        val messages = mutableListOf<GroqChatMessage>()

        // 1. Build system instruction with temporal context & memories
        val zoneId = java.time.ZoneId.of(timeProvider.getZoneId())
        val zonedDateTime = java.time.ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timeProvider.currentTimeMillis()), zoneId
        )
        val formattedDate = zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val formattedTime = zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val dayOfWeek = zonedDateTime.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val timezoneStr = timeProvider.getZoneId()

        val timeContext = """
            CurrentDate: $formattedDate
            CurrentTime: $formattedTime
            Timezone: $timezoneStr
            CurrentDayOfWeek: $dayOfWeek
        """.trimIndent()

        val baseSystem = request.systemInstruction ?: DEFAULT_SYSTEM_INSTRUCTION

        val systemText = buildString {
            append(baseSystem)
            append("\n\n--- TEMPORAL CONTEXT ---\n")
            append(timeContext)

            if (!request.languageHint.isNullOrBlank()) {
                append("\n\n--- LANGUAGE CONTEXT ---\n")
                append("Target response language context: ${request.languageHint}")
            }

            if (request.relevantMemories.isNotEmpty()) {
                val memoriesContext = request.relevantMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }
                append("\n\nYou possess the following relevant personal background info about the user. Use it naturally to personalize your answers. Do NOT mention that you are using stored memories unless explicitly asked:\n")
                append(memoriesContext)
            }

            if (!request.fileContext.isNullOrBlank()) {
                append("\n\n--- LOCAL FILE CONTEXT ---\n")
                append(request.fileContext)
                append("\n\nFor file-specific questions, rely only on the supplied file excerpts. If the excerpts are insufficient, say that clearly. Do not invent missing file content.")
            }
        }

        messages.add(GroqChatMessage(role = "system", content = systemText))

        // 2. Add history messages
        request.history.forEach { msg ->
            val role = when (msg.role) {
                ProviderRole.SYSTEM -> "system"
                ProviderRole.USER -> "user"
                ProviderRole.ASSISTANT -> "assistant"
            }
            if (msg.content.isNotBlank()) {
                messages.add(GroqChatMessage(role = role, content = msg.content))
            }
        }

        // 3. Add current user prompt if not already the last message in history
        val lastMsg = messages.lastOrNull()
        if (lastMsg == null || lastMsg.role != "user" || lastMsg.content != request.userMessage) {
            if (request.userMessage.isNotBlank()) {
                messages.add(GroqChatMessage(role = "user", content = request.userMessage))
            }
        }

        val resolvedModel = modelProvider().ifBlank { DEFAULT_MODEL }

        return GroqChatCompletionRequest(
            model = resolvedModel,
            messages = messages,
            stream = stream
        )
    }

    internal fun mapToProviderException(e: Exception): ProviderException {
        val rawMessage = e.localizedMessage ?: e.message ?: "Unknown error"
        val safeMessage = PrivacyUtil.sanitizeErrorMessage(rawMessage)
            .replace(Regex("Bearer\\s+[A-Za-z0-9_\\-\\.]+", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .replace(Regex("gsk_[A-Za-z0-9_]+", RegexOption.IGNORE_CASE), "[REDACTED]")

        return when (e) {
            is ProviderException -> e
            is HttpException -> {
                when (e.code()) {
                    401, 403 -> ProviderException.AuthenticationError("Groq authentication failed (code ${e.code()}): $safeMessage", e)
                    429 -> ProviderException.RateLimitError("Groq rate limit exceeded (code 429): $safeMessage", e)
                    in 500..599 -> ProviderException.ProviderUnavailableError("Groq service unavailable (code ${e.code()}): $safeMessage", e)
                    in 400..499 -> ProviderException.InvalidRequestError("Groq invalid request (code ${e.code()}): $safeMessage", e)
                    else -> ProviderException.UnknownError("Groq HTTP exception (code ${e.code()}): $safeMessage", e)
                }
            }
            is java.net.SocketTimeoutException -> ProviderException.NetworkError("Groq connection timed out: $safeMessage", e)
            is java.net.UnknownHostException -> ProviderException.NetworkError("Groq host unreachable: $safeMessage", e)
            is IOException -> ProviderException.NetworkError("Groq network error: $safeMessage", e)
            else -> ProviderException.UnknownError("Unexpected Groq error: $safeMessage", e)
        }
    }
}
