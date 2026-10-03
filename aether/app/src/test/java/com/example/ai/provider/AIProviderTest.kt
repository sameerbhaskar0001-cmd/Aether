package com.example.ai.provider

import com.example.ai.SystemTimeProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.provider.models.ProviderErrorCategory
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderRole
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.data.model.Memory
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AIProviderTest {

    private class MockTestProvider(
        override val type: AIProviderType = AIProviderType.GEMINI,
        override val providerName: String = "MockProvider"
    ) : AIProvider {
        var lastRequest: ProviderRequest? = null

        override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
            lastRequest = request
            emit(ProviderStreamChunk("Hello ", false, providerName))
            emit(ProviderStreamChunk("Hello world!", true, providerName))
        }

        override suspend fun generate(request: ProviderRequest): ProviderResponse {
            lastRequest = request
            return ProviderResponse("Complete response", providerName)
        }
    }

    @Test
    fun testAIProviderContractWithMock() = runBlocking {
        val provider = MockTestProvider()
        val request = ProviderRequest(
            userMessage = "Test prompt",
            history = listOf(
                ProviderMessage(ProviderRole.USER, "Hi"),
                ProviderMessage(ProviderRole.ASSISTANT, "Hello")
            ),
            relevantMemories = listOf(
                Memory(
                    id = "mem1",
                    content = "User prefers Kotlin",
                    category = "Preferences",
                    createdTimestamp = 1000L,
                    lastUpdatedTimestamp = 1000L,
                    sourceConversationId = null
                )
            ),
            systemInstruction = "Custom instruction",
            languageHint = "English"
        )

        val chunks = provider.generateStream(request).toList()
        assertEquals(2, chunks.size)
        assertEquals("Hello ", chunks[0].textDelta)
        assertEquals("Hello world!", chunks[1].textDelta)
        assertTrue(chunks[1].isComplete)
        assertEquals("MockProvider", chunks[1].providerName)

        val singleResponse = provider.generate(request)
        assertEquals("Complete response", singleResponse.text)
        assertEquals("MockProvider", singleResponse.providerName)

        assertEquals("Test prompt", provider.lastRequest?.userMessage)
        assertEquals("Custom instruction", provider.lastRequest?.systemInstruction)
        assertEquals("English", provider.lastRequest?.languageHint)
    }

    @Test
    fun testAIProviderFactorySelection() {
        val factory = AIProviderFactory()
        val provider = factory.getProvider(AIProviderType.GEMINI)

        assertNotNull(provider)
        assertEquals(AIProviderType.GEMINI, provider.type)
        assertEquals("Gemini", provider.providerName)
    }

    @Test
    fun testAIProviderFactoryCustomMap() {
        val mockProvider = MockTestProvider(providerName = "CustomMock")
        val factory = AIProviderFactory(providerMap = mapOf(AIProviderType.GEMINI to mockProvider))

        val provider = factory.getProvider(AIProviderType.GEMINI)
        assertEquals("CustomMock", provider.providerName)
    }

    @Test
    fun testGeminiProviderMissingKeyEmitsAuthErrorChunk() = runBlocking {
        val geminiProvider = GeminiProvider(apiKeyProvider = { "" })
        val request = ProviderRequest(userMessage = "Hello")

        val chunks = geminiProvider.generateStream(request).toList()
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].isComplete)
        assertTrue(chunks[0].textDelta.contains("API Key is not configured"))
    }

    @Test
    fun testGeminiProviderMissingKeyNonStreamingThrowsAuthError() = runBlocking {
        val geminiProvider = GeminiProvider(apiKeyProvider = { "" })
        val request = ProviderRequest(userMessage = "Hello")

        var caught: Exception? = null
        try {
            geminiProvider.generate(request)
        } catch (e: Exception) {
            caught = e
        }

        assertNotNull(caught)
        assertTrue(caught is ProviderException.AuthenticationError)
        assertEquals(ProviderErrorCategory.AUTHENTICATION_ERROR, (caught as ProviderException).category)
    }

    @Test
    fun testProviderExceptionErrorCategories() {
        val netErr = ProviderException.NetworkError("No connection")
        assertEquals(ProviderErrorCategory.NETWORK_ERROR, netErr.category)

        val authErr = ProviderException.AuthenticationError("Invalid API key")
        assertEquals(ProviderErrorCategory.AUTHENTICATION_ERROR, authErr.category)

        val rateErr = ProviderException.RateLimitError("Too many requests")
        assertEquals(ProviderErrorCategory.RATE_LIMIT_ERROR, rateErr.category)

        val unavailErr = ProviderException.ProviderUnavailableError("Server 503")
        assertEquals(ProviderErrorCategory.PROVIDER_UNAVAILABLE, unavailErr.category)

        val invalidErr = ProviderException.InvalidRequestError("Bad payload")
        assertEquals(ProviderErrorCategory.INVALID_REQUEST, invalidErr.category)

        val unknownErr = ProviderException.UnknownError("Unexpected exception")
        assertEquals(ProviderErrorCategory.UNKNOWN_ERROR, unknownErr.category)
    }

    @Test
    fun testGeminiAssistantServiceAdapterIntegration() = runBlocking {
        val mockProvider = MockTestProvider()
        val adapterService = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            provider = mockProvider
        )

        val history = listOf(
            Message(id = "1", content = "Hello", sender = Sender.USER, status = MessageStatus.SENT),
            Message(id = "2", content = "Hi there", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val memories = listOf(
            Memory(
                id = "mem1",
                content = "User works in Android",
                category = "Career",
                createdTimestamp = 1000L,
                lastUpdatedTimestamp = 1000L,
                sourceConversationId = null
            )
        )

        val textChunks = adapterService.generateResponseStream("What should I build?", history, memories).toList()
        assertEquals(2, textChunks.size)
        assertEquals("Hello ", textChunks[0])
        assertEquals("Hello world!", textChunks[1])

        val receivedReq = mockProvider.lastRequest
        assertNotNull(receivedReq)
        assertEquals("What should I build?", receivedReq?.userMessage)
        assertEquals(2, receivedReq?.history?.size)
        assertEquals(ProviderRole.USER, receivedReq?.history?.get(0)?.role)
        assertEquals("Hello", receivedReq?.history?.get(0)?.content)
        assertEquals(ProviderRole.ASSISTANT, receivedReq?.history?.get(1)?.role)
        assertEquals("Hi there", receivedReq?.history?.get(1)?.content)
        assertEquals(1, receivedReq?.relevantMemories?.size)
    }
}
