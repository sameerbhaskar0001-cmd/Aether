package com.example.ai.provider

import android.util.Log
import com.example.BuildConfig
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
import com.example.ai.model.GeminiToolConfig
import com.example.ai.network.GeminiApiService
import com.example.ai.network.RetrofitClient
import com.example.ai.provider.metadata.CapabilitySupport
import com.example.ai.provider.metadata.ProviderCapabilities
import com.example.ai.provider.models.ProviderErrorCategory
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderMessage
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
import retrofit2.HttpException
import java.io.IOException
import java.lang.Exception
import java.lang.StringBuilder

class GeminiProvider(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY },
    private val toolRegistry: ToolRegistry? = null,
    private val toolOrchestrator: ToolOrchestrator? = null,
    private val apiService: GeminiApiService = RetrofitClient.service
) : AIProvider {

    override val type: AIProviderType = AIProviderType.GEMINI
    override val providerName: String = "Gemini"
    override val capabilities: ProviderCapabilities = CAPABILITIES

    companion object {
        private const val TAG = "GeminiProvider"
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
                    providerName = providerName
                )
            )
            return@flow
        }

        val generateRequest = buildGeminiRequest(request)

        try {
            val response = apiService.generateContentStream(apiKey, generateRequest)
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
                                    textDelta = currentText.toString(),
                                    isComplete = false,
                                    providerName = providerName
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
                val secondResponse = apiService.generateContentStream(apiKey, secondRequest)
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
                                        providerName = providerName
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
                    val fallbackResponse = apiService.generateContent(apiKey, secondRequest)
                    val text = fallbackResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    val finalText = if (!text.isNullOrBlank()) text else "Aether returned an empty response."
                    emit(
                        ProviderStreamChunk(
                            textDelta = finalText,
                            isComplete = true,
                            providerName = providerName
                        )
                    )
                } else {
                    emit(
                        ProviderStreamChunk(
                            textDelta = secondText.toString(),
                            isComplete = true,
                            providerName = providerName
                        )
                    )
                }
            } else {
                if (currentText.isEmpty()) {
                    val fallbackResponse = apiService.generateContent(apiKey, generateRequest)
                    val text = fallbackResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    val finalText = if (!text.isNullOrBlank()) {
                        text
                    } else {
                        "Aether returned an empty response. Please try reframing your instruction."
                    }
                    emit(
                        ProviderStreamChunk(
                            textDelta = finalText,
                            isComplete = true,
                            providerName = providerName
                        )
                    )
                } else {
                    emit(
                        ProviderStreamChunk(
                            textDelta = currentText.toString(),
                            isComplete = true,
                            providerName = providerName
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

        var generateRequest = buildGeminiRequest(request)

        try {
            var response = apiService.generateContent(apiKey, generateRequest)
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

                response = apiService.generateContent(apiKey, secondRequest)
            }

            val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!text.isNullOrBlank()) {
                ProviderResponse(text = text, providerName = providerName)
            } else {
                ProviderResponse(
                    text = "Aether returned an empty response. Please try reframing your instruction.",
                    providerName = providerName
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

        // 2. Add user prompt if not already the last turn
        val lastContent = contents.lastOrNull()
        if (lastContent == null || lastContent.parts.firstOrNull()?.text != request.userMessage || lastContent.role != "user") {
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
