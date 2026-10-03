package com.example.ai.provider

import com.example.ai.SystemTimeProvider
import com.example.ai.provider.openrouter.OpenRouterApiService
import com.example.ai.provider.openrouter.OpenRouterProvider
import com.example.ai.provider.openrouter.models.OpenRouterChatRequest
import com.example.ai.provider.openrouter.models.OpenRouterChatResponse
import com.example.ai.provider.openrouter.models.OpenRouterChoice
import com.example.ai.provider.openrouter.models.OpenRouterChatMessage
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenRouterProviderTest {

    private lateinit var fakeApiService: FakeOpenRouterApiService
    private lateinit var provider: OpenRouterProvider

    class FakeOpenRouterApiService : OpenRouterApiService {
        var responseToReturn: OpenRouterChatResponse? = null
        var streamResponseBody: ResponseBody? = null
        var lastRequest: OpenRouterChatRequest? = null

        override suspend fun generateChatCompletion(
            authHeader: String,
            request: OpenRouterChatRequest
        ): OpenRouterChatResponse {
            lastRequest = request
            return responseToReturn ?: OpenRouterChatResponse()
        }

        override suspend fun generateChatCompletionStream(
            authHeader: String,
            request: OpenRouterChatRequest
        ): ResponseBody {
            lastRequest = request
            return streamResponseBody ?: "data: {\"choices\":[{\"delta\":{\"content\":\"Streaming result\"}}]}\n\ndata: [DONE]\n".toResponseBody()
        }
    }

    @Before
    fun setUp() {
        fakeApiService = FakeOpenRouterApiService()
        provider = OpenRouterProvider(
            timeProvider = SystemTimeProvider(),
            apiKeyProvider = { "fake_openrouter_api_key_123" },
            modelProvider = { "qwen/qwen-2-7b-instruct:free" },
            apiService = fakeApiService
        )
    }

    @Test
    fun testProviderCreationAndConfiguration() {
        assertEquals(AIProviderType.OPENROUTER, provider.type)
        assertEquals("OpenRouter", provider.providerName)
    }

    @Test
    fun testMissingApiKeyThrowsAuthenticationError() = runBlocking {
        val badProvider = OpenRouterProvider(
            apiKeyProvider = { "" },
            apiService = fakeApiService
        )

        // Non-streaming
        try {
            badProvider.generate(ProviderRequest(userMessage = "hello"))
            fail("Expected AuthenticationError")
        } catch (e: ProviderException.AuthenticationError) {
            assertNotNull(e.message)
        }

        // Streaming returns a safe error chunk
        val chunks = badProvider.generateStream(ProviderRequest(userMessage = "hello")).toList()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().textDelta.contains("API Key is not configured"))
    }

    @Test
    fun testSuccessfulResponseMapping() = runBlocking {
        fakeApiService.responseToReturn = OpenRouterChatResponse(
            choices = listOf(
                OpenRouterChoice(
                    message = OpenRouterChatMessage(role = "assistant", content = "OpenRouter response body.")
                )
            )
        )

        val request = ProviderRequest(userMessage = "Calculate 15 + 23")
        val response = provider.generate(request)

        assertEquals("OpenRouter response body.", response.text)
        assertEquals("OpenRouter", response.providerName)
        assertNotNull(fakeApiService.lastRequest)
        assertEquals("qwen/qwen-2-7b-instruct:free", fakeApiService.lastRequest?.model)
    }

    @Test
    fun testStreamingResponseMapping() = runBlocking {
        fakeApiService.streamResponseBody = (
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"OpenRouter!\"}}]}\n" +
            "data: [DONE]\n"
        ).toResponseBody()

        val request = ProviderRequest(userMessage = "Hello streaming")
        val chunks = provider.generateStream(request).toList()

        assertEquals(2, chunks.size)
        assertEquals("Hello ", chunks[0].textDelta)
        assertEquals("Hello ", chunks[0].accumulatedText)
        assertEquals("OpenRouter!", chunks[1].textDelta)
        assertEquals("Hello OpenRouter!", chunks[1].accumulatedText)
    }

    @Test
    fun testApiNetworkErrorMapping() = runBlocking {
        val errorApiService = object : OpenRouterApiService {
            override suspend fun generateChatCompletion(
                authHeader: String,
                request: OpenRouterChatRequest
            ): OpenRouterChatResponse {
                throw IOException("No internet connection")
            }

            override suspend fun generateChatCompletionStream(
                authHeader: String,
                request: OpenRouterChatRequest
            ): ResponseBody {
                throw IOException("No internet connection")
            }
        }

        val badProvider = OpenRouterProvider(
            apiKeyProvider = { "valid_key" },
            apiService = errorApiService
        )

        try {
            badProvider.generate(ProviderRequest(userMessage = "test"))
            fail("Expected NetworkError")
        } catch (e: ProviderException.NetworkError) {
            assertTrue(e.message?.contains("Network connection error") ?: false)
        }
    }

    @Test
    fun testModelConfiguration() {
        val customModelProvider = OpenRouterProvider(
            apiKeyProvider = { "key" },
            modelProvider = { "meta-llama/llama-3-70b-instruct" },
            apiService = fakeApiService
        )

        runBlocking {
            fakeApiService.responseToReturn = OpenRouterChatResponse(
                choices = listOf(
                    OpenRouterChoice(
                        message = OpenRouterChatMessage(role = "assistant", content = "Custom model response")
                    )
                )
            )

            customModelProvider.generate(ProviderRequest(userMessage = "custom model test"))
            assertEquals("meta-llama/llama-3-70b-instruct", fakeApiService.lastRequest?.model)
        }
    }
}
