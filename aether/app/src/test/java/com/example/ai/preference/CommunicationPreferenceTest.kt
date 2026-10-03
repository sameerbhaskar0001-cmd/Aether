package com.example.ai.preference

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunicationPreferenceTest {

    @Before
    fun setUp() {
        CommunicationPreferenceStore.clear()
    }

    @Test
    fun testExplicitPersistentPreferenceDetection() {
        val msg = "From now on, keep answers concise."
        val pref = CommunicationPreferenceDetector.detectPreference(msg)
        assertNotNull(pref)
        assertEquals(PreferenceCategory.RESPONSE_LENGTH, pref?.category)
        assertEquals("CONCISE", pref?.value)
    }

    @Test
    fun testOneTimeInstructionRejection() {
        val msg = "Explain this briefly."
        val pref = CommunicationPreferenceDetector.detectPreference(msg)
        assertNull(pref) // Should be rejected since there's no persistence keywords
    }

    @Test
    fun testPreferenceCreation() {
        CommunicationPreferenceDetector.processMessageForPreferences("Remember that I prefer simple explanations.")
        val pref = CommunicationPreferenceStore.getPreference(PreferenceCategory.EXPLANATION_DEPTH)
        assertNotNull(pref)
        assertEquals("BEGINNER", pref?.value)
    }

    @Test
    fun testPreferenceUpdateOrReplacement() {
        // Initial preference
        CommunicationPreferenceDetector.processMessageForPreferences("Remember that I prefer simple explanations.")
        assertEquals("BEGINNER", CommunicationPreferenceStore.getPreference(PreferenceCategory.EXPLANATION_DEPTH)?.value)

        // Update preference
        CommunicationPreferenceDetector.processMessageForPreferences("Always explain concepts at an advanced expert level.")
        assertEquals("ADVANCED", CommunicationPreferenceStore.getPreference(PreferenceCategory.EXPLANATION_DEPTH)?.value)
    }

    @Test
    fun testExplicitPreferenceRemoval() {
        CommunicationPreferenceDetector.processMessageForPreferences("Always keep answers concise.")
        assertNotNull(CommunicationPreferenceStore.getPreference(PreferenceCategory.RESPONSE_LENGTH))

        CommunicationPreferenceDetector.processMessageForPreferences("Stop keeping answers concise.")
        assertNull(CommunicationPreferenceStore.getPreference(PreferenceCategory.RESPONSE_LENGTH))
    }

    @Test
    fun testMultipleIndependentPreferenceCategories() {
        CommunicationPreferenceDetector.processMessageForPreferences("From now on, keep answers concise.")
        CommunicationPreferenceDetector.processMessageForPreferences("Always use casual chill tone please.")

        val lengthPref = CommunicationPreferenceStore.getPreference(PreferenceCategory.RESPONSE_LENGTH)
        val tonePref = CommunicationPreferenceStore.getPreference(PreferenceCategory.TONE)

        assertNotNull(lengthPref)
        assertNotNull(tonePref)
        assertEquals("CONCISE", lengthPref?.value)
        assertEquals("CASUAL", tonePref?.value)
    }

    @Test
    fun testPreferenceInjectionIntoProviderRequest() = runBlocking {
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

        // Set persistent preference
        CommunicationPreferenceDetector.processMessageForPreferences("Always keep answers concise.")

        assistantService.generateResponseStream(
            userMessage = "Tell me about cars.",
            history = emptyList(),
            relevantMemories = emptyList()
        ).toList()

        assertNotNull(capturedRequest)
        val hint = capturedRequest?.languageHint
        assertNotNull(hint)
        assertTrue(hint!!.contains("Communication Preference Guidelines:"))
        assertTrue(hint.contains("Keep your response extremely concise"))
    }

    @Test
    fun testNoInferredPersonalityPreference() {
        val msg = "Always remember that I am extremely anxious and impatient."
        val pref = CommunicationPreferenceDetector.detectPreference(msg)
        assertNull(pref) // Rejected due to personality traits
    }

    @Test
    fun testNoSensitivePreferenceStorage() {
        val msg = "Always remember that my password is superSecret123"
        val pref1 = CommunicationPreferenceDetector.detectPreference(msg)
        assertNull(pref1) // Rejected due to password pattern

        val msg2 = "Always remember that my email is test@example.com"
        val pref2 = CommunicationPreferenceDetector.detectPreference(msg2)
        assertNull(pref2) // Rejected due to email pattern
    }
}

// Extension function to match existing call patterns
private suspend fun GeminiAssistantService.generateResponseStream(
    userMessage: String,
    history: List<Message>,
    relevantMemories: List<Any>
): Flow<String> {
    return generateResponseStream(userMessage, history, emptyList())
}
