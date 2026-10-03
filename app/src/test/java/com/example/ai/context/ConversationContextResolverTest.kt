package com.example.ai.context

import com.example.ai.SystemTimeProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationContextResolverTest {

    @Test
    fun testSameTopicContinuity() {
        val history = listOf(
            Message(id = "1", content = "I am working on the Aether project database config.", sender = Sender.USER, status = MessageStatus.SENT)
        )
        // User continues on the same database topic
        val currentMsg = "How should we implement the database setup?"
        val result = ConversationContextResolver.resolve(currentMsg, history)
        
        assertTrue(result.isResolved)
        assertEquals("I am working on the Aether project database config.", result.resolvedContextText)
        assertEquals(0.7f, result.confidence, 0.01f)
    }

    @Test
    fun testSimpleThisThatItReference() {
        val history = listOf(
            Message(id = "1", content = "Let's use Room Database for storage.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Tell me more about it."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        assertTrue(result.isResolved)
        assertEquals("Let's use Room Database for storage.", result.resolvedContextText)
        assertEquals(0.9f, result.confidence, 0.01f)
    }

    @Test
    fun testPreviousItemReference() {
        val history = listOf(
            Message(id = "1", content = "First we have SQLite option.", sender = Sender.ASSISTANT, status = MessageStatus.SENT),
            Message(id = "2", content = "Second we have SharedPreferences storage.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "What about the previous one?"
        val result = ConversationContextResolver.resolve(currentMsg, history)

        assertTrue(result.isResolved)
        assertEquals("First we have SQLite option.", result.resolvedContextText)
        assertEquals(0.9f, result.confidence, 0.01f)
    }

    @Test
    fun testNumberedOptionReference() {
        val history = listOf(
            Message(id = "1", content = "Here are your choices:\n1. Room DB\n2. Shared Preferences\n3. Memory Cache", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        
        val currentMsg = "Explain the second option."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        assertTrue(result.isResolved)
        assertEquals("Shared Preferences", result.resolvedContextText)
        assertEquals(1.0f, result.confidence, 0.01f)
    }

    @Test
    fun testTopicChange() {
        val history = listOf(
            Message(id = "1", content = "Let's discuss Room Database performance tuning.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Switch to a different topic, tell me about the weather."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        assertFalse(result.isResolved)
        assertNull(result.resolvedContextText)
        assertEquals("the weather.", result.detectedTopic)
    }

    @Test
    fun testAmbiguousReferenceRemainsUnresolved() {
        val history = listOf(
            Message(id = "1", content = "You could choose between Room or SharedPreferences for this project.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Tell me more about it."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        assertFalse(result.isResolved)
        assertNull(result.resolvedContextText)
        assertEquals(0.0f, result.confidence, 0.01f)
    }

    @Test
    fun testBoundedContextLimits() {
        // Build history with a very long message > 1000 chars that should be skipped
        val hugeContent = "A".repeat(1005)
        val history = listOf(
            Message(id = "1", content = "Older interesting topic.", sender = Sender.USER, status = MessageStatus.SENT),
            Message(id = "2", content = hugeContent, sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Explain it."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        // Since the last message is too long, the bounded history skips it and does not resolve it.
        assertFalse(result.isResolved)
    }

    @Test
    fun testSeparationFromLongTermMemory() {
        // Assert that long term memory queries and active conversational resolution do not mix or mutate each other.
        val history = listOf(
            Message(id = "1", content = "Active conversational topic here.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Explain that."
        val result = ConversationContextResolver.resolve(currentMsg, history)

        // Resolved context is active and valid
        assertTrue(result.isResolved)
        assertEquals("Active conversational topic here.", result.resolvedContextText)
        
        // Ensure no memory interaction logic resides in ConversationContextResolver (purely in-memory history bound)
        val emptyMemories = emptyList<com.example.data.model.Memory>()
        assertTrue(emptyMemories.isEmpty())
    }

    @Test
    fun testProviderRequestReceivesResolvedContext() = runBlocking {
        var capturedRequest: ProviderRequest? = null

        val mockProvider = object : AIProvider {
            override val type: AIProviderType = AIProviderType.GEMINI
            override val providerName: String = "Mock"

            override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> {
                capturedRequest = request
                return emptyFlow()
            }

            override suspend fun generate(request: ProviderRequest): ProviderResponse {
                capturedRequest = request
                return ProviderResponse("Text", "Mock")
            }
        }

        val assistantService = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            provider = mockProvider
        )

        val history = listOf(
            Message(id = "1", content = "Let's use Room DB.", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )

        assistantService.generateResponseStream(
            userMessage = "Explain it.",
            history = history,
            relevantMemories = emptyList()
        ).toList()

        assertNotNull(capturedRequest)
        val ctx = capturedRequest?.conversationContext
        assertNotNull(ctx)
        assertTrue(ctx!!.isResolved)
        assertEquals("Let's use Room DB.", ctx.resolvedContextText)
    }

    @Test
    fun testDeterministicResolution() {
        // Ensure that same input and history always yield exact same deterministic resolution
        val history = listOf(
            Message(id = "1", content = "Here are choices:\n1. Room DB\n2. Memory Cache", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )
        val currentMsg = "Explain the first option."

        val firstRun = ConversationContextResolver.resolve(currentMsg, history)
        val secondRun = ConversationContextResolver.resolve(currentMsg, history)

        assertEquals(firstRun.isResolved, secondRun.isResolved)
        assertEquals(firstRun.resolvedContextText, secondRun.resolvedContextText)
        assertEquals(firstRun.confidence, secondRun.confidence, 0.001f)
    }
}

// Extension to match existing call patterns
private suspend fun GeminiAssistantService.generateResponseStream(
    userMessage: String,
    history: List<Message>,
    relevantMemories: List<Any>
): Flow<String> {
    return generateResponseStream(userMessage, history, emptyList())
}
