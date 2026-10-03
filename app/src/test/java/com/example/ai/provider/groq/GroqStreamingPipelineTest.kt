package com.example.ai.provider.groq

import com.example.ai.GeminiAssistantService
import com.example.ai.SystemTimeProvider
import com.example.ai.provider.AIProviderFactory
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.groq.models.GroqChatCompletionRequest
import com.example.ai.provider.groq.models.GroqChatCompletionResponse
import com.example.ai.provider.models.ProviderRequest
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroqStreamingPipelineTest {

    private class FakeGroqApiService(
        private val sseData: String
    ) : GroqApiService {
        override suspend fun generateChatCompletionStream(
            authHeader: String,
            request: GroqChatCompletionRequest
        ): ResponseBody {
            return sseData.toResponseBody("text/event-stream".toMediaType())
        }

        override suspend fun generateChatCompletion(
            authHeader: String,
            request: GroqChatCompletionRequest
        ): GroqChatCompletionResponse {
            throw NotImplementedError()
        }
    }

    @Test
    fun testGroqStreamingAccumulatedTextPipeline() = runBlocking {
        val sseStream = """
            data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}
            data: {"choices":[{"delta":{"content":" world"},"finish_reason":null}]}
            data: {"choices":[{"delta":{"content":"."},"finish_reason":"stop"}]}
            data: [DONE]
        """.trimIndent()

        val fakeApiService = FakeGroqApiService(sseStream)
        val groqProvider = GroqProvider(
            timeProvider = SystemTimeProvider(),
            apiKeyProvider = { "gsk_valid_test_key" },
            apiService = fakeApiService
        )

        val factory = AIProviderFactory(
            timeProvider = SystemTimeProvider(),
            providerMap = mapOf(
                AIProviderType.GROQ to groqProvider
            )
        )

        val selectionManager = com.example.ai.provider.selection.ModelSelectionManager(
            context = org.robolectric.RuntimeEnvironment.getApplication()
        )
        selectionManager.setSelectionMode(com.example.ai.provider.selection.SelectionMode.MANUAL, isIncognito = false)
        selectionManager.setSelectedProvider(AIProviderType.GROQ, isIncognito = false)

        val assistantService = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            providerFactory = factory,
            selectionManager = selectionManager
        )

        val emittedChunks = assistantService.generateResponseStream(
            userMessage = "Hello Groq",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false
        ).toList()

        // Verify emissions accumulate text instead of replacing with single dot
        assertEquals(3, emittedChunks.size)
        assertEquals("Hello", emittedChunks[0])
        assertEquals("Hello world", emittedChunks[1])
        assertEquals("Hello world.", emittedChunks[2])

        // Verify final emitted string is the full response
        val finalResult = emittedChunks.last()
        assertEquals("Hello world.", finalResult)
        assertTrue("Final string must contain full text, not just period", finalResult.length > 1)
    }
}
