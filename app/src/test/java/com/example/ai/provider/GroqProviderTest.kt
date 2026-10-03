package com.example.ai.provider

import com.example.ai.SystemTimeProvider
import com.example.ai.provider.groq.GroqApiService
import com.example.ai.provider.groq.GroqProvider
import com.example.ai.provider.groq.models.GroqChatCompletionRequest
import com.example.ai.provider.groq.models.GroqChatCompletionResponse
import com.example.ai.provider.groq.models.GroqChoice
import com.example.ai.provider.groq.models.GroqChatMessage
import com.example.ai.provider.models.ProviderErrorCategory
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderRole
import com.example.data.model.Memory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroqProviderTest {

    private class FakeGroqApiService(
        var streamResponseBody: ResponseBody? = null,
        var nonStreamResponse: GroqChatCompletionResponse? = null,
        var shouldThrowException: Exception? = null
    ) : GroqApiService {
        var lastAuthHeader: String? = null
        var lastStreamRequest: GroqChatCompletionRequest? = null
        var lastNonStreamRequest: GroqChatCompletionRequest? = null

        override suspend fun generateChatCompletionStream(
            authHeader: String,
            request: GroqChatCompletionRequest
        ): ResponseBody {
            lastAuthHeader = authHeader
            lastStreamRequest = request
            shouldThrowException?.let { throw it }
            return streamResponseBody ?: "".toResponseBody("text/event-stream".toMediaType())
        }

        override suspend fun generateChatCompletion(
            authHeader: String,
            request: GroqChatCompletionRequest
        ): GroqChatCompletionResponse {
            lastAuthHeader = authHeader
            lastNonStreamRequest = request
            shouldThrowException?.let { throw it }
            return nonStreamResponse ?: GroqChatCompletionResponse(
                id = "mock-id",
                model = request.model,
                choices = listOf(
                    GroqChoice(
                        index = 0,
                        message = GroqChatMessage(role = "assistant", content = "Mock response"),
                        finishReason = "stop"
                    )
                )
            )
        }
    }

    @Test
    fun testGroqProviderRoleAndMessageMapping() {
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_api_key" },
            modelProvider = { "openai/gpt-oss-20b" }
        )

        val memories = listOf(
            Memory(
                id = "mem1",
                content = "User prefers concise answers",
                category = "Preferences",
                createdTimestamp = 1000L,
                lastUpdatedTimestamp = 1000L,
                sourceConversationId = null
            )
        )

        val history = listOf(
            ProviderMessage(role = ProviderRole.SYSTEM, content = "System initial context"),
            ProviderMessage(role = ProviderRole.USER, content = "User previous question"),
            ProviderMessage(role = ProviderRole.ASSISTANT, content = "Assistant previous reply")
        )

        val request = ProviderRequest(
            userMessage = "What is the capital of France?",
            history = history,
            relevantMemories = memories,
            systemInstruction = "You are Aether.",
            languageHint = "English"
        )

        val groqReq = provider.buildGroqRequest(request, stream = true)

        assertEquals("openai/gpt-oss-20b", groqReq.model)
        assertTrue(groqReq.stream)
        assertEquals(5, groqReq.messages.size)

        // 1. System instruction
        assertEquals("system", groqReq.messages[0].role)
        assertTrue(groqReq.messages[0].content.contains("You are Aether."))
        assertTrue(groqReq.messages[0].content.contains("TEMPORAL CONTEXT"))
        assertTrue(groqReq.messages[0].content.contains("Target response language context: English"))
        assertTrue(groqReq.messages[0].content.contains("User prefers concise answers"))

        // 2. History
        assertEquals("system", groqReq.messages[1].role)
        assertEquals("System initial context", groqReq.messages[1].content)

        assertEquals("user", groqReq.messages[2].role)
        assertEquals("User previous question", groqReq.messages[2].content)

        assertEquals("assistant", groqReq.messages[3].role)
        assertEquals("Assistant previous reply", groqReq.messages[3].content)

        // 3. Current user message
        assertEquals("user", groqReq.messages[4].role)
        assertEquals("What is the capital of France?", groqReq.messages[4].content)
    }

    @Test
    fun testCurrentUserMessageNotDuplicatedIfAlreadyLastInHistory() {
        val provider = GroqProvider(apiKeyProvider = { "gsk_test_api_key" })
        val request = ProviderRequest(
            userMessage = "Already asked",
            history = listOf(
                ProviderMessage(role = ProviderRole.USER, content = "Already asked")
            )
        )

        val groqReq = provider.buildGroqRequest(request, stream = false)
        // Should have 2 messages: system instruction + "Already asked" once
        assertEquals(2, groqReq.messages.size)
        assertEquals("system", groqReq.messages[0].role)
        assertEquals("user", groqReq.messages[1].role)
        assertEquals("Already asked", groqReq.messages[1].content)
    }

    @Test
    fun testGroqConfigurableModel() {
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_api_key" },
            modelProvider = { "custom-groq-model-v2" }
        )
        val request = ProviderRequest(userMessage = "Hello")
        val groqReq = provider.buildGroqRequest(request, stream = false)
        assertEquals("custom-groq-model-v2", groqReq.model)
    }

    @Test
    fun testGroqDefaultModelFallback() {
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_api_key" },
            modelProvider = { "" }
        )
        val request = ProviderRequest(userMessage = "Hello")
        val groqReq = provider.buildGroqRequest(request, stream = false)
        assertEquals("openai/gpt-oss-20b", groqReq.model)
        assertEquals("openai/gpt-oss-20b", GroqProvider.DEFAULT_MODEL)
    }

    @Test
    fun testMissingApiKeyStreamingEmitsAuthError() = runBlocking {
        val provider = GroqProvider(apiKeyProvider = { "" })
        val request = ProviderRequest(userMessage = "Hello")

        val chunks = provider.generateStream(request).toList()
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].isComplete)
        assertEquals("GROQ", chunks[0].providerName)
        assertTrue(chunks[0].textDelta.contains("Groq API Key is not configured"))
    }

    @Test
    fun testMissingApiKeyNonStreamingThrowsAuthError() = runBlocking {
        val provider = GroqProvider(apiKeyProvider = { "MY_GROQ_API_KEY" })
        val request = ProviderRequest(userMessage = "Hello")

        var caught: Exception? = null
        try {
            provider.generate(request)
        } catch (e: Exception) {
            caught = e
        }

        assertNotNull(caught)
        assertTrue(caught is ProviderException.AuthenticationError)
        assertEquals(ProviderErrorCategory.AUTHENTICATION_ERROR, (caught as ProviderException).category)
    }

    @Test
    fun testStreamingSuccessWithSseParsing() = runBlocking {
        val sseData = """
            data: {"choices":[{"delta":{"content":"Groq "},"finish_reason":null}]}
            data: {"choices":[{"delta":{"content":"streaming "},"finish_reason":null}]}
            data: {"choices":[{"delta":{"content":"works!"},"finish_reason":"stop"}]}
            data: [DONE]
        """.trimIndent()

        val fakeService = FakeGroqApiService(
            streamResponseBody = sseData.toResponseBody("text/event-stream".toMediaType())
        )
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_key" },
            apiService = fakeService
        )

        val request = ProviderRequest(userMessage = "Test stream")
        val chunks = provider.generateStream(request).toList()

        assertEquals(3, chunks.size)
        assertEquals("Groq ", chunks[0].textDelta)
        assertFalse(chunks[0].isComplete)
        assertEquals("GROQ", chunks[0].providerName)
        assertEquals("Groq ", chunks[0].accumulatedText)

        assertEquals("streaming ", chunks[1].textDelta)
        assertFalse(chunks[1].isComplete)
        assertEquals("Groq streaming ", chunks[1].accumulatedText)

        assertEquals("works!", chunks[2].textDelta)
        assertTrue(chunks[2].isComplete)
        assertEquals("Groq streaming works!", chunks[2].accumulatedText)

        assertEquals("Bearer gsk_test_key", fakeService.lastAuthHeader)
    }

    @Test
    fun testStreamingEmptyFallsBackToNonStreaming() = runBlocking {
        val fakeService = FakeGroqApiService(
            streamResponseBody = "".toResponseBody("text/event-stream".toMediaType()),
            nonStreamResponse = GroqChatCompletionResponse(
                id = "fallback-id",
                model = "openai/gpt-oss-20b",
                choices = listOf(
                    GroqChoice(
                        index = 0,
                        message = GroqChatMessage(role = "assistant", content = "Fallback non-streaming response"),
                        finishReason = "stop"
                    )
                )
            )
        )
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_key" },
            apiService = fakeService
        )

        val chunks = provider.generateStream(ProviderRequest(userMessage = "Empty stream")).toList()
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].isComplete)
        assertEquals("Fallback non-streaming response", chunks[0].textDelta)
        assertEquals("GROQ", chunks[0].providerName)
    }

    @Test
    fun testNonStreamingSuccess() = runBlocking {
        val fakeService = FakeGroqApiService(
            nonStreamResponse = GroqChatCompletionResponse(
                id = "resp-1",
                model = "openai/gpt-oss-20b",
                choices = listOf(
                    GroqChoice(
                        index = 0,
                        message = GroqChatMessage(role = "assistant", content = "Direct response"),
                        finishReason = "stop"
                    )
                )
            )
        )
        val provider = GroqProvider(
            apiKeyProvider = { "gsk_test_key" },
            apiService = fakeService
        )

        val response = provider.generate(ProviderRequest(userMessage = "Test non-streaming"))
        assertEquals("Direct response", response.text)
        assertEquals("GROQ", response.providerName)
        assertEquals("Bearer gsk_test_key", fakeService.lastAuthHeader)
    }

    @Test
    fun testErrorMappingCategories() {
        val provider = GroqProvider(apiKeyProvider = { "gsk_test_key" })

        // 401 -> AuthenticationError
        val http401 = HttpException(Response.error<Any>(401, "Unauthorized".toResponseBody()))
        val mapped401 = provider.mapToProviderException(http401)
        assertTrue(mapped401 is ProviderException.AuthenticationError)
        assertEquals(ProviderErrorCategory.AUTHENTICATION_ERROR, mapped401.category)

        // 403 -> AuthenticationError
        val http403 = HttpException(Response.error<Any>(403, "Forbidden".toResponseBody()))
        val mapped403 = provider.mapToProviderException(http403)
        assertTrue(mapped403 is ProviderException.AuthenticationError)
        assertEquals(ProviderErrorCategory.AUTHENTICATION_ERROR, mapped403.category)

        // 429 -> RateLimitError
        val http429 = HttpException(Response.error<Any>(429, "Too Many Requests".toResponseBody()))
        val mapped429 = provider.mapToProviderException(http429)
        assertTrue(mapped429 is ProviderException.RateLimitError)
        assertEquals(ProviderErrorCategory.RATE_LIMIT_ERROR, mapped429.category)

        // 500 / 503 -> ProviderUnavailableError
        val http503 = HttpException(Response.error<Any>(503, "Service Unavailable".toResponseBody()))
        val mapped503 = provider.mapToProviderException(http503)
        assertTrue(mapped503 is ProviderException.ProviderUnavailableError)
        assertEquals(ProviderErrorCategory.PROVIDER_UNAVAILABLE, mapped503.category)

        // 400 -> InvalidRequestError
        val http400 = HttpException(Response.error<Any>(400, "Bad Request".toResponseBody()))
        val mapped400 = provider.mapToProviderException(http400)
        assertTrue(mapped400 is ProviderException.InvalidRequestError)
        assertEquals(ProviderErrorCategory.INVALID_REQUEST, mapped400.category)

        // Timeout / IO -> NetworkError
        val timeout = SocketTimeoutException("Read timed out")
        val mappedTimeout = provider.mapToProviderException(timeout)
        assertTrue(mappedTimeout is ProviderException.NetworkError)
        assertEquals(ProviderErrorCategory.NETWORK_ERROR, mappedTimeout.category)

        val io = IOException("Connection reset")
        val mappedIO = provider.mapToProviderException(io)
        assertTrue(mappedIO is ProviderException.NetworkError)
        assertEquals(ProviderErrorCategory.NETWORK_ERROR, mappedIO.category)

        // Unexpected -> UnknownError
        val unexpected = IllegalStateException("Something broke")
        val mappedUnexpected = provider.mapToProviderException(unexpected)
        assertTrue(mappedUnexpected is ProviderException.UnknownError)
        assertEquals(ProviderErrorCategory.UNKNOWN_ERROR, mappedUnexpected.category)
    }

    @Test
    fun testApiKeyAndAuthHeaderNeverAppearInExceptions() {
        val provider = GroqProvider(apiKeyProvider = { "gsk_super_secret_123456789" })
        val rawException = IOException("Failed connecting to url with Bearer gsk_super_secret_123456789 in header")

        val mapped = provider.mapToProviderException(rawException)
        assertFalse(mapped.message?.contains("gsk_super_secret_123456789") ?: false)
        assertTrue(mapped.message?.contains("[REDACTED]") ?: false)
    }

    @Test
    fun testAIProviderFactorySelectsGroqAndGemini() {
        val factory = AIProviderFactory()

        val gemini = factory.getProvider(AIProviderType.GEMINI)
        assertNotNull(gemini)
        assertEquals(AIProviderType.GEMINI, gemini.type)
        assertEquals("Gemini", gemini.providerName)

        val groq = factory.getProvider(AIProviderType.GROQ)
        assertNotNull(groq)
        assertEquals(AIProviderType.GROQ, groq.type)
        assertEquals("GROQ", groq.providerName)
    }
}
