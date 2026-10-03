package com.example.ai.language

import com.example.ai.SystemTimeProvider
import com.example.ai.GeminiAssistantService
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
class LanguageDetectorTest {

    @Test
    fun testEnglishDetection() {
        val result = LanguageDetector.detect("Hello my friend, how are you doing today? Let's build some Kotlin code.")
        assertEquals(DetectedLanguage.ENGLISH, result.language)
        assertTrue(result.confidence > 0.4f)
        assertFalse(result.isExplicitOverride)
    }

    @Test
    fun testHindiDetection() {
        val result = LanguageDetector.detect("नमस्कार भाई, आप कैसे हैं? आज क्या काम करना है?")
        assertEquals(DetectedLanguage.HINDI, result.language)
        assertTrue(result.confidence > 0.4f)
        assertFalse(result.isExplicitOverride)
    }

    @Test
    fun testHinglishDetection() {
        val result = LanguageDetector.detect("bhai kya chal raha hai? theek hai na? chalo kam start karte hain.")
        assertEquals(DetectedLanguage.HINGLISH, result.language)
        assertTrue(result.confidence > 0.4f)
        assertFalse(result.isExplicitOverride)
    }

    @Test
    fun testSpanishDetection() {
        val result = LanguageDetector.detect("Hola mi amigo, ¿cómo estás hoy? Quiero aprender español por favor.")
        assertEquals(DetectedLanguage.SPANISH, result.language)
        assertTrue(result.confidence > 0.4f)
        assertFalse(result.isExplicitOverride)
    }

    @Test
    fun testBengaliDetection() {
        val result = LanguageDetector.detect("কেমন আছেন? বাংলা ভাষা খুব সুন্দর।")
        assertEquals(DetectedLanguage.BENGALI, result.language)
        assertTrue(result.confidence > 0.4f)
        assertFalse(result.isExplicitOverride)
    }

    @Test
    fun testMixedLanguageHandling() {
        // Spanish and English mixed, Spanish dominant in keywords
        val mixed1 = LanguageDetector.detect("Hola, let's learn español because it is a very beautiful language, gracias.")
        assertEquals(DetectedLanguage.SPANISH, mixed1.language)

        // Hindi (Latin) / Hinglish dominance
        val mixed2 = LanguageDetector.detect("Hello buddy, kaise ho? kya chal rha hai?")
        assertEquals(DetectedLanguage.HINGLISH, mixed2.language)
    }

    @Test
    fun testLowConfidenceFallback() {
        // Short message "ok" has low confidence on detection
        val currentMsg = "ok"
        val fallbackCtx = LanguageDetector.determineLanguageContext(
            userMessage = currentMsg,
            history = listOf("¿cómo estás?", "hola mi amigo")
        )

        assertEquals(DetectedLanguage.SPANISH, fallbackCtx.language)
        // Check fallback works with Hinglish history too
        val fallbackHinglish = LanguageDetector.determineLanguageContext(
            userMessage = "sure",
            history = listOf("bhai kya ho rha hai?", "sab badhiya hai")
        )
        assertEquals(DetectedLanguage.HINGLISH, fallbackHinglish.language)
    }

    @Test
    fun testExplicitLanguageOverride() {
        // Text contains command override
        val overrideEn = LanguageDetector.detect("Write in English please.")
        assertEquals(DetectedLanguage.ENGLISH, overrideEn.language)
        assertTrue(overrideEn.isExplicitOverride)

        val overrideHi = LanguageDetector.detect("Please write in Hindi from now on.")
        assertEquals(DetectedLanguage.HINDI, overrideHi.language)
        assertTrue(overrideHi.isExplicitOverride)

        val overrideHinglish = LanguageDetector.detect("speak in hinglish")
        assertEquals(DetectedLanguage.HINGLISH, overrideHinglish.language)
        assertTrue(overrideHinglish.isExplicitOverride)

        val overrideEs = LanguageDetector.detect("respond in spanish")
        assertEquals(DetectedLanguage.SPANISH, overrideEs.language)
        assertTrue(overrideEs.isExplicitOverride)
    }

    @Test
    fun testProviderRequestReceivesLanguageInstruction() = runBlocking {
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
            Message(id = "1", content = "bhai kya chal rha h", sender = Sender.USER, status = MessageStatus.SENT),
            Message(id = "2", content = "All good buddy", sender = Sender.ASSISTANT, status = MessageStatus.SENT)
        )

        // High confidence Spanish prompt should directly set Spanish instruction
        assistantService.generateResponseStream(
            userMessage = "¿cómo estás amigo?",
            history = history,
            relevantMemories = emptyList()
        ).toList()

        assertNotNull(capturedRequest)
        assertTrue(capturedRequest?.languageHint?.contains("respond in Spanish") ?: false)

        // Low confidence response "ok" should fallback to recent user history "bhai kya chal rha h" (Hinglish)
        assistantService.generateResponseStream(
            userMessage = "ok",
            history = history,
            relevantMemories = emptyList()
        ).toList()

        assertNotNull(capturedRequest)
        assertTrue(capturedRequest?.languageHint?.contains("respond in Hinglish") ?: false)
    }
}
