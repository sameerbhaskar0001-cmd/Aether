package com.example.ai.style

import com.example.ai.SystemTimeProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.language.DetectedLanguage
import com.example.ai.language.LanguageContext
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderMessage
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
class ConversationStyleDetectorTest {

    @Test
    fun testCasualDetection() {
        val result = ConversationStyleDetector.detect("hey bro what's up? let's do something cool and chill today!")
        assertEquals(ConversationStyle.CASUAL, result.primaryStyle)
    }

    @Test
    fun testProfessionalDetection() {
        val result = ConversationStyleDetector.detect("Dear colleague, regarding the scheduled project deliverables, I appreciate your time.")
        assertEquals(ConversationStyle.PROFESSIONAL, result.primaryStyle)
    }

    @Test
    fun testTechnicalDetection() {
        val result = ConversationStyleDetector.detect("Write a kotlin class with a custom thread-safe database function using gradle.")
        assertEquals(ConversationStyle.TECHNICAL, result.primaryStyle)
    }

    @Test
    fun testBeginnerFriendlyRequest() {
        val result = ConversationStyleDetector.detect("how do i start learning kotlin? can you give a simple explanation for beginners?")
        assertEquals(ConversationStyle.BEGINNER_FRIENDLY, result.primaryStyle)
    }

    @Test
    fun testConciseRequest() {
        // Short text < 20 characters
        val result = ConversationStyleDetector.detect("ok, but why?")
        assertEquals(ConversationStyle.CONCISE, result.primaryStyle)
    }

    @Test
    fun testDetailedRequest() {
        // Long text > 250 characters
        val longText = "I need a comprehensive breakdown. First, outline the setup. Second, show the exact class structures. Third, explain step-by-step how the data flow progresses from the controller through the repository to the database layer. Finally, provide details on performance caching strategy."
        val result = ConversationStyleDetector.detect(longText)
        assertEquals(ConversationStyle.DETAILED, result.primaryStyle)
    }

    @Test
    fun testExplicitStyleOverride() {
        val result = ConversationStyleDetector.detect("be professional and explain simply please")
        assertTrue(result.isExplicitOverride)
        assertEquals(ConversationStyle.BEGINNER_FRIENDLY, result.primaryStyle)
        assertTrue(result.additionalStyles.contains(ConversationStyle.PROFESSIONAL))
    }

    @Test
    fun testLanguageAndStyleCombinedInstruction() {
        val langContext = LanguageContext(DetectedLanguage.HINDI, 0.9f)
        val styleContext = ConversationStyleContext(
            primaryStyle = ConversationStyle.TECHNICAL,
            additionalStyles = setOf(ConversationStyle.CONCISE)
        )
        val combined = ConversationStyleDetector.buildCombinedInstruction(langContext, styleContext)
        
        assertNotNull(combined)
        assertTrue(combined!!.contains("You must respond in Hindi"))
        assertTrue(combined.contains("Style guidelines:"))
        assertTrue(combined.contains("technical, precise tone"))
        assertTrue(combined.contains("Keep your response extremely concise"))
    }

    @Test
    fun testNeutralOrUnknownFallback() {
        // Normal text without strong indicators fallbacks to history style
        val currentMsg = "Let's see."
        val fallbackCtx = ConversationStyleDetector.determineStyleContext(
            userMessage = currentMsg,
            history = listOf("hey bro, yo what's up")
        )
        assertEquals(ConversationStyle.CASUAL, fallbackCtx.primaryStyle)
    }

    @Test
    fun testEndToEndGeminiAssistantServiceIntegration() = runBlocking {
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
            Message(id = "1", content = "hey bro, what's up?", sender = Sender.USER, status = MessageStatus.SENT),
            Message(id = "2", content = "Not much, you?", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )

        // User says "explain simply" (override)
        assistantService.generateResponseStream(
            userMessage = "explain simply how compilers work",
            history = history,
            relevantMergeries = emptyList()
        ).toList()

        assertNotNull(capturedRequest)
        val hint = capturedRequest?.languageHint
        assertNotNull(hint)
        assertTrue(hint!!.contains("Style guidelines:"))
        assertTrue(hint.contains("beginner-friendly terms"))
    }
}

// Extension to allow compilation of mock test while passing empty list for memories
private suspend fun GeminiAssistantService.generateResponseStream(
    userMessage: String,
    history: List<Message>,
    relevantMergeries: List<Any>
): Flow<String> {
    return generateResponseStream(userMessage, history, emptyList())
}
