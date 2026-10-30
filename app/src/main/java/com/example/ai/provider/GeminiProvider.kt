package com.example.ai.provider

import android.util.Log
import com.example.ai.SystemTimeProvider
import com.example.ai.TimeProvider
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.GenerateContentResponse
import com.example.ai.model.Part
import com.example.ai.model.GeminiFunctionDeclaration
import com.example.ai.model.GeminiSchema
import com.example.ai.model.GeminiFunctionCall
import com.example.ai.model.GeminiFunctionResponse
import com.example.ai.model.GeminiInlineData
import com.example.ai.model.GeminiToolConfig
import com.example.ai.network.GeminiApiService
import com.example.ai.network.RetrofitClient
import com.example.ai.provider.metadata.CapabilitySupport
import com.example.ai.provider.metadata.ProviderCapabilities
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderRole
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.orchestrator.ToolOrchestrator
import com.example.ai.tool.model.ToolCall
import com.example.ai.tool.model.ToolParameterType
import com.example.util.PrivacyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.lang.Exception
import java.lang.StringBuilder

class GeminiProvider(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val apiKeyProvider: () -> String = { com.example.util.ApiKeyStorage.getGeminiKey() },
    private val toolRegistry: ToolRegistry? = null,
    private val toolOrchestrator: ToolOrchestrator? = null,
    private val apiService: GeminiApiService = RetrofitClient.service,
    private val dynamicApiService: com.example.ai.network.GeminiDynamicApiService = RetrofitClient.dynamicService
) : AIProvider {

    override val type: AIProviderType = AIProviderType.GEMINI
    override val providerName: String = "Gemini"
    override val capabilities: ProviderCapabilities = CAPABILITIES

    @Volatile
    var lastActiveModelId: String = DEFAULT_MODEL_ID
        private set

    @Volatile
    var lastCallUsedFallback: Boolean = false
        private set

    private data class ResolvedModelCall<T>(
        val data: T,
        val activeModelId: String,
        val isFallback: Boolean
    )

    companion object {
        private const val TAG = "GeminiProvider"
        const val DEFAULT_MODEL_ID = "gemini-3.8-flash"
        const val PRO_MODEL_ID = "gemini-3.1-pro-preview"
        const val LIVE_VERIFIED_FALLBACK_ID = "gemini-3.5-flash"
        const val SECONDARY_FALLBACK_ID = "gemini-flash-latest"
        const val FALLBACK_MODEL_ID = "gemini-2.0-flash"

        val OFFICIAL_MODEL_CATALOG = setOf(
            DEFAULT_MODEL_ID,
            PRO_MODEL_ID,
            LIVE_VERIFIED_FALLBACK_ID,
            SECONDARY_FALLBACK_ID,
            FALLBACK_MODEL_ID,
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )

        fun normalizeModelId(rawModelId: String?): String {
            return when (val cleaned = rawModelId?.trim().orEmpty()) {
                "", "gemini-3.8-flash", DEFAULT_MODEL_ID -> DEFAULT_MODEL_ID
                "gemini-3.1-pro-preview", PRO_MODEL_ID -> PRO_MODEL_ID
                "gemini-3.8-pro", "gemini-2.5-pro" -> PRO_MODEL_ID
                "gemini-2.5-flash" -> DEFAULT_MODEL_ID
                LIVE_VERIFIED_FALLBACK_ID -> LIVE_VERIFIED_FALLBACK_ID
                SECONDARY_FALLBACK_ID -> SECONDARY_FALLBACK_ID
                FALLBACK_MODEL_ID -> FALLBACK_MODEL_ID
                else -> if (cleaned in OFFICIAL_MODEL_CATALOG) cleaned else DEFAULT_MODEL_ID
            }
        }

        fun fallbackChainFor(requestedModelId: String): List<String> {
            return when (normalizeModelId(requestedModelId)) {
                PRO_MODEL_ID -> listOf(DEFAULT_MODEL_ID, LIVE_VERIFIED_FALLBACK_ID, SECONDARY_FALLBACK_ID)
                DEFAULT_MODEL_ID -> listOf(LIVE_VERIFIED_FALLBACK_ID, SECONDARY_FALLBACK_ID, FALLBACK_MODEL_ID)
                else -> listOf(LIVE_VERIFIED_FALLBACK_ID, SECONDARY_FALLBACK_ID, FALLBACK_MODEL_ID)
            }
        }

        val CAPABILITIES = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.SUPPORTED,
            vision = CapabilitySupport.UNKNOWN,
            webSearch = CapabilitySupport.SUPPORTED,
            maxContextTokens = 1048576
        )
        private const val DEFAULT_SYSTEM_INSTRUCTION =
            "You are Aether, an autonomous cognitive workspace assistant designed for absolute focus and zero noise. " +
            "Respond directly, concisely, and accurately without conversational filler or pleasantries unless requested."
    }

    private fun shouldAttemptModelFallback(e: Exception): Boolean {
        return when (e) {
            is HttpException -> e.code() == 404 || e.code() == 503 || e.code() == 429
            is java.io.IOException -> true
            else -> false
        }
    }

    private suspend fun invokeStreamForModel(
        modelId: String,
        apiKey: String,
        generateRequest: GenerateContentRequest
    ): ResponseBody {
        val useDynamic = modelId != DEFAULT_MODEL_ID || dynamicApiService !== RetrofitClient.dynamicService
        return if (useDynamic && (apiService === RetrofitClient.service || dynamicApiService !== RetrofitClient.dynamicService)) {
            dynamicApiService.generateContentStream(modelId, apiKey, generateRequest)
        } else {
            apiService.generateContentStream(apiKey, generateRequest)
        }
    }

    private suspend fun invokeGenerateForModel(
        modelId: String,
        apiKey: String,
        generateRequest: GenerateContentRequest
    ): GenerateContentResponse {
        val useDynamic = modelId != DEFAULT_MODEL_ID || dynamicApiService !== RetrofitClient.dynamicService
        return if (useDynamic && (apiService === RetrofitClient.service || dynamicApiService !== RetrofitClient.dynamicService)) {
            dynamicApiService.generateContent(modelId, apiKey, generateRequest)
        } else {
            apiService.generateContent(apiKey, generateRequest)
        }
    }

    private suspend fun executeStreamCall(
        apiKey: String,
        generateRequest: GenerateContentRequest,
        preferredModelId: String?
    ): ResolvedModelCall<ResponseBody> {
        val primaryModel = normalizeModelId(preferredModelId)
        val wasOriginallyInvalid = !preferredModelId.isNullOrBlank() && preferredModelId.trim() !in OFFICIAL_MODEL_CATALOG
        try {
            val body = invokeStreamForModel(primaryModel, apiKey, generateRequest)
            lastActiveModelId = primaryModel
            lastCallUsedFallback = wasOriginallyInvalid
            return ResolvedModelCall(body, primaryModel, wasOriginallyInvalid)
        } catch (e: Exception) {
            if (!shouldAttemptModelFallback(e)) throw e
            var lastError: Exception = e
            val canTryDynamicFallbacks = apiService === RetrofitClient.service || dynamicApiService !== RetrofitClient.dynamicService
            if (canTryDynamicFallbacks) {
                for (fallbackModel in fallbackChainFor(primaryModel)) {
                    try {
                        val body = invokeStreamForModel(fallbackModel, apiKey, generateRequest)
                        lastActiveModelId = fallbackModel
                        lastCallUsedFallback = true
                        return ResolvedModelCall(body, fallbackModel, true)
                    } catch (fallbackErr: Exception) {
                        lastError = fallbackErr
                        if (!shouldAttemptModelFallback(fallbackErr)) throw fallbackErr
                    }
                }
            }
            throw lastError
        }
    }

    private suspend fun executeGenerateCall(
        apiKey: String,
        generateRequest: GenerateContentRequest,
        preferredModelId: String?
    ): ResolvedModelCall<GenerateContentResponse> {
        val primaryModel = normalizeModelId(preferredModelId)
        val wasOriginallyInvalid = !preferredModelId.isNullOrBlank() && preferredModelId.trim() !in OFFICIAL_MODEL_CATALOG
        try {
            val response = invokeGenerateForModel(primaryModel, apiKey, generateRequest)
            lastActiveModelId = primaryModel
            lastCallUsedFallback = wasOriginallyInvalid
            return ResolvedModelCall(response, primaryModel, wasOriginallyInvalid)
        } catch (e: Exception) {
            if (!shouldAttemptModelFallback(e)) throw e
            var lastError: Exception = e
            val canTryDynamicFallbacks = apiService === RetrofitClient.service || dynamicApiService !== RetrofitClient.dynamicService
            if (canTryDynamicFallbacks) {
                for (fallbackModel in fallbackChainFor(primaryModel)) {
                    try {
                        val response = invokeGenerateForModel(fallbackModel, apiKey, generateRequest)
                        lastActiveModelId = fallbackModel
                        lastCallUsedFallback = true
                        return ResolvedModelCall(response, fallbackModel, true)
                    } catch (fallbackErr: Exception) {
                        lastError = fallbackErr
                        if (!shouldAttemptModelFallback(fallbackErr)) throw fallbackErr
                    }
                }
            }
            throw lastError
        }
    }

    override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
        val apiKey = apiKeyProvider()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val authErr = ProviderException.AuthenticationError(
                "API Key is not configured. Please add your Gemini API Key in the Secrets panel in Google AI Studio to unlock Aether's autonomous cognitive assistant."
            )
            emit(
                ProviderStreamChunk(
                    textDelta = authErr.message ?: "API Key missing",
                    isComplete = true,
                    providerName = providerName,
                    activeModelId = normalizeModelId(request.preferredModelId),
                    isModelFallback = false
                )
            )
            return@flow
        }

        val generateRequest = buildGeminiRequest(request)

        try {
            val resolvedStream = executeStreamCall(apiKey, generateRequest, request.preferredModelId)
            val response = resolvedStream.data
            var activeModel = resolvedStream.activeModelId
            var usedFallback = resolvedStream.isFallback
            val currentText = StringBuilder()
            var detectedFunctionCall: GeminiFunctionCall? = null

            response.byteStream().bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line?.trim() ?: continue
                    if (!rawLine.startsWith("data:")) continue
                    val jsonPayload = rawLine.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue
                    try {
                        val adapter = RetrofitClient.moshi.adapter(GenerateContentResponse::class.java)
                        val responseObj = adapter.fromJson(jsonPayload)
                        val candidate = responseObj?.candidates?.firstOrNull()
                        val part = candidate?.content?.parts?.firstOrNull()

                        if (part?.functionCall != null) {
                            detectedFunctionCall = part.functionCall
                            break // Interrupted stream due to function call!
                        }

                        val textChunk = part?.text
                        if (textChunk != null) {
                            currentText.append(textChunk)
                            emit(
                                ProviderStreamChunk(
                                    textDelta = textChunk,
                                    isComplete = false,
                                    providerName = providerName,
                                    accumulatedText = currentText.toString(),
                                    activeModelId = activeModel,
                                    isModelFallback = usedFallback
                                )
                            )
                        }
                    } catch (e: Exception) {
                        val safeErr = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                        Log.e(TAG, "Error parsing stream chunk: $safeErr")
                    }
                }
            }

            if (detectedFunctionCall != null && toolOrchestrator != null) {
                val fCall = detectedFunctionCall!!
                val rawArgs = fCall.args ?: emptyMap()
                val finalArgs = if (request.isIncognito) {
                    rawArgs.toMutableMap().apply { put("is_incognito", true) }
                } else {
                    rawArgs
                }
                val toolCall = ToolCall(
                    toolName = fCall.name,
                    arguments = finalArgs,
                    callId = "call_" + java.util.UUID.randomUUID().toString().take(8)
                )

                // Execute the tool call neutrally
                val orchestratorResult = toolOrchestrator.execute(toolCall)

                val modelPart = Part(functionCall = fCall)
                val modelContent = Content(role = "model", parts = listOf(modelPart))

                val responseMap = mapOf(
                    "content" to orchestratorResult.result.content,
                    "status" to orchestratorResult.result.status.name,
                    "errorMessage" to orchestratorResult.result.errorMessage
                )
                val functionResponse = GeminiFunctionResponse(
                    name = fCall.name,
                    response = responseMap
                )
                val responsePart = Part(functionResponse = functionResponse)
                val responseContent = Content(role = "function", parts = listOf(responsePart))

                val nextContents = generateRequest.contents.toMutableList()
                nextContents.add(modelContent)
                nextContents.add(responseContent)

                val secondRequest = generateRequest.copy(contents = nextContents)

                // Stream second turn output
                val resolvedSecondStream = executeStreamCall(apiKey, secondRequest, activeModel)
                val secondResponse = resolvedSecondStream.data
                activeModel = resolvedSecondStream.activeModelId
                usedFallback = usedFallback || resolvedSecondStream.isFallback
                val secondText = StringBuilder()

                secondResponse.byteStream().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val rawLine = line?.trim() ?: continue
                        if (!rawLine.startsWith("data:")) continue
                        val jsonPayload = rawLine.removePrefix("data:").trim()
                        if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue
                        try {
                            val adapter = RetrofitClient.moshi.adapter(GenerateContentResponse::class.java)
                            val responseObj = adapter.fromJson(jsonPayload)
                            val textChunk = responseObj?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                            if (textChunk != null) {
                                secondText.append(textChunk)
                                emit(
                                    ProviderStreamChunk(
                                        textDelta = secondText.toString(),
                                        isComplete = false,
                                        providerName = providerName,
                                        activeModelId = activeModel,
                                        isModelFallback = usedFallback
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            val safeErr = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                            Log.e(TAG, "Error parsing stream chunk (turn 2): $safeErr")
                        }
                    }
                }

                if (secondText.isEmpty()) {
                    val fallbackResolved = executeGenerateCall(apiKey, secondRequest, activeModel)
                    activeModel = fallbackResolved.activeModelId
                    usedFallback = usedFallback || fallbackResolved.isFallback
                    val text = fallbackResolved.data.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    val finalText = if (!text.isNullOrBlank()) text else "Aether returned an empty response."
                    emit(
                        ProviderStreamChunk(
                            textDelta = finalText,
                            isComplete = true,
                            providerName = providerName,
                            activeModelId = activeModel,
                            isModelFallback = usedFallback
                        )
                    )
                } else {
                    emit(
                        ProviderStreamChunk(
                            textDelta = secondText.toString(),
                            isComplete = true,
                            providerName = providerName,
                            activeModelId = activeModel,
                            isModelFallback = usedFallback
                        )
                    )
                }
            } else {
                if (currentText.isEmpty()) {
                    val fallbackResolved = executeGenerateCall(apiKey, generateRequest, activeModel)
                    activeModel = fallbackResolved.activeModelId
                    usedFallback = usedFallback || fallbackResolved.isFallback
                    val text = fallbackResolved.data.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    val finalText = if (!text.isNullOrBlank()) {
                        text
                    } else {
                        "Aether returned an empty response. Please try reframing your instruction."
                    }
                    emit(
                        ProviderStreamChunk(
                            textDelta = finalText,
                            isComplete = true,
                            providerName = providerName,
                            activeModelId = activeModel,
                            isModelFallback = usedFallback
                        )
                    )
                } else {
                    emit(
                        ProviderStreamChunk(
                            textDelta = currentText.toString(),
                            isComplete = true,
                            providerName = providerName,
                            activeModelId = activeModel,
                            isModelFallback = usedFallback
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val mappedException = mapToProviderException(e)
            val safeErr = PrivacyUtil.sanitizeErrorMessage(mappedException.message)
            Log.e(TAG, "Gemini API stream failed: $safeErr", e)
            throw mappedException
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generate(request: ProviderRequest): ProviderResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw ProviderException.AuthenticationError("API Key is missing or invalid.")
        }

        val generateRequest = buildGeminiRequest(request)

        try {
            var resolvedCall = executeGenerateCall(apiKey, generateRequest, request.preferredModelId)
            var response = resolvedCall.data
            var activeModel = resolvedCall.activeModelId
            var usedFallback = resolvedCall.isFallback
            val candidate = response.candidates?.firstOrNull()
            val functionCall = candidate?.content?.parts?.firstOrNull()?.functionCall

            if (functionCall != null && toolOrchestrator != null) {
                val rawArgs = functionCall.args ?: emptyMap()
                val finalArgs = if (request.isIncognito) {
                    rawArgs.toMutableMap().apply { put("is_incognito", true) }
                } else {
                    rawArgs
                }
                val toolCall = ToolCall(
                    toolName = functionCall.name,
                    arguments = finalArgs,
                    callId = "call_" + java.util.UUID.randomUUID().toString().take(8)
                )

                // Execute the tool call neutrally
                val orchestratorResult = toolOrchestrator.execute(toolCall)

                val modelPart = Part(functionCall = functionCall)
                val modelContent = Content(role = "model", parts = listOf(modelPart))

                val responseMap = mapOf(
                    "content" to orchestratorResult.result.content,
                    "status" to orchestratorResult.result.status.name,
                    "errorMessage" to orchestratorResult.result.errorMessage
                )
                val functionResponse = GeminiFunctionResponse(
                    name = functionCall.name,
                    response = responseMap
                )
                val responsePart = Part(functionResponse = functionResponse)
                val responseContent = Content(role = "function", parts = listOf(responsePart))

                val nextContents = generateRequest.contents.toMutableList()
                nextContents.add(modelContent)
                nextContents.add(responseContent)

                val secondRequest = generateRequest.copy(contents = nextContents)

                resolvedCall = executeGenerateCall(apiKey, secondRequest, activeModel)
                response = resolvedCall.data
                activeModel = resolvedCall.activeModelId
                usedFallback = usedFallback || resolvedCall.isFallback
            }

            val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!text.isNullOrBlank()) {
                ProviderResponse(
                    text = text,
                    providerName = providerName,
                    activeModelId = activeModel,
                    isModelFallback = usedFallback
                )
            } else {
                ProviderResponse(
                    text = "Aether returned an empty response. Please try reframing your instruction.",
                    providerName = providerName,
                    activeModelId = activeModel,
                    isModelFallback = usedFallback
                )
            }
        } catch (e: Exception) {
            val mappedException = mapToProviderException(e)
            val safeErr = PrivacyUtil.sanitizeErrorMessage(mappedException.message)
            Log.e(TAG, "Gemini API non-streaming call failed: $safeErr", e)
            throw mappedException
        }
    }

    private fun buildGeminiRequest(request: ProviderRequest): GenerateContentRequest {
        val contents = mutableListOf<Content>()

        // 1. Add history items
        request.history.forEach { msg ->
            val roleStr = when (msg.role) {
                ProviderRole.USER -> "user"
                ProviderRole.ASSISTANT -> "model"
                ProviderRole.SYSTEM -> "user"
            }
            if (msg.content.isNotBlank()) {
                contents.add(
                    Content(
                        role = roleStr,
                        parts = listOf(Part(text = msg.content))
                    )
                )
            }
        }

        // 2. Add user prompt (and optional multimodal image attachment)
        val lastContent = contents.lastOrNull()
        val hasImage = request.imageAttachment != null && request.imageAttachment.base64Data.isNotBlank()
        if (hasImage) {
            val userParts = mutableListOf<Part>()
            val attachment = request.imageAttachment!!
            userParts.add(
                Part(
                    inlineData = GeminiInlineData(
                        mimeType = attachment.mimeType,
                        data = attachment.base64Data
                    )
                )
            )
            userParts.add(
                Part(
                    text = request.userMessage.ifBlank { "Analyze this image in detail." }
                )
            )
            if (lastContent != null && lastContent.role == "user" && lastContent.parts.firstOrNull()?.text == request.userMessage) {
                contents[contents.lastIndex] = Content(role = "user", parts = userParts)
            } else {
                contents.add(Content(role = "user", parts = userParts))
            }
        } else if (lastContent == null || lastContent.parts.firstOrNull()?.text != request.userMessage || lastContent.role != "user") {
            if (request.userMessage.isNotBlank()) {
                contents.add(
                    Content(
                        role = "user",
                        parts = listOf(Part(text = request.userMessage))
                    )
                )
            }
        }

        // 3. Build system instruction with temporal context & memories
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

        val systemInstructionText = buildString {
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

        // 4. Build tool definitions list neutrally
        val toolConfigs = mutableListOf<GeminiToolConfig>()
        if (toolRegistry != null && toolRegistry.size > 0) {
            val declarations = toolRegistry.getDefinitions().map { def ->
                val props = def.parameters.associate { param ->
                    val typeStr = when (param.type) {
                        ToolParameterType.STRING -> "STRING"
                        ToolParameterType.NUMBER -> "NUMBER"
                        ToolParameterType.BOOLEAN -> "BOOLEAN"
                        ToolParameterType.ARRAY -> "ARRAY"
                        ToolParameterType.OBJECT -> "OBJECT"
                    }
                    param.name to GeminiSchema(type = typeStr, description = param.description)
                }
                GeminiFunctionDeclaration(
                    name = def.name,
                    description = def.description,
                    parameters = if (props.isNotEmpty()) {
                        GeminiSchema(
                            type = "OBJECT",
                            properties = props,
                            required = def.parameters.filter { it.isRequired }.map { it.name }.ifEmpty { null }
                        )
                    } else null
                )
            }
            toolConfigs.add(GeminiToolConfig(functionDeclarations = declarations))
        }

        return GenerateContentRequest(
            contents = contents,
            systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
            tools = toolConfigs.ifEmpty { null }
        )
    }

    private fun mapToProviderException(e: Exception): ProviderException {
        val safeMessage = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage ?: e.message ?: "Unknown error")
        return when (e) {
            is ProviderException -> e
            is HttpException -> {
                when (e.code()) {
                    401, 403 -> ProviderException.AuthenticationError("Authentication failed ($safeMessage)", e)
                    429 -> ProviderException.RateLimitError("Rate limit exceeded ($safeMessage)", e)
                    in 500..599 -> ProviderException.ProviderUnavailableError("Provider service unavailable ($safeMessage)", e)
                    in 400..499 -> ProviderException.InvalidRequestError("Invalid request ($safeMessage)", e)
                    else -> ProviderException.UnknownError("HTTP Exception ${e.code()}: $safeMessage", e)
                }
            }
            is IOException -> ProviderException.NetworkError("Network connection error: $safeMessage", e)
            else -> ProviderException.UnknownError("Unexpected provider error: $safeMessage", e)
        }
    }
}
