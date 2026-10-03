package com.example.ai.provider.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.intent.TaskIntent
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.provider.selection.SelectionMode
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
class IntentRoutingIntegrationTest {

    private lateinit var context: Context
    private lateinit var extractor: TaskRequirementExtractor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        extractor = DefaultTaskRequirementExtractor()
    }

    private fun createTestSelectionManager(
        availableTypes: List<AIProviderType> = listOf(AIProviderType.GEMINI, AIProviderType.GROQ, AIProviderType.OPENROUTER)
    ): ModelSelectionManager {
        return object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = type in availableTypes
        }
    }

    @Test
    fun testVisionIntentToVisionRequirement() {
        val request = ProviderRequest(
            userMessage = "What is shown in this image?",
            hasVisionInput = true
        )
        val profile = extractor.extract(request)

        assertTrue(profile.requiresVision)
        assertEquals(TaskIntent.VISION_ANALYSIS, profile.inferredIntent?.primaryIntent)
    }

    @Test
    fun testWebResearchIntentToWebSearchRequirement() {
        val request = ProviderRequest(
            userMessage = "What is today's stock price for GOOG?",
            requiresWebSearch = true
        )
        val profile = extractor.extract(request)

        assertTrue(profile.requiresWebSearch)
        assertEquals(TaskIntent.WEB_RESEARCH, profile.inferredIntent?.primaryIntent)
    }

    @Test
    fun testFileAnalysisNoAutomaticToolCallingRequirement() {
        val request = ProviderRequest(
            userMessage = "Summarize this quarterly financial statement",
            fileContext = "Revenue: $10M, Net Income: $2M",
            requiresToolCalling = false
        )
        val profile = extractor.extract(request)

        assertEquals(TaskIntent.FILE_ANALYSIS, profile.inferredIntent?.primaryIntent)
        assertFalse(profile.requiresToolCalling)
        assertFalse(profile.requiresVision)
        assertFalse(profile.requiresWebSearch)
    }

    @Test
    fun testExplicitStructuredFlagsOverrideInferredIntent() {
        val request = ProviderRequest(
            userMessage = "Just ordinary text here",
            requiresToolCalling = true,
            hasVisionInput = true,
            requiresWebSearch = true,
            userPreferredProvider = AIProviderType.OPENROUTER
        )
        val profile = extractor.extract(request)

        assertTrue(profile.requiresToolCalling)
        assertTrue(profile.requiresVision)
        assertTrue(profile.requiresWebSearch)
        assertEquals(AIProviderType.OPENROUTER, profile.userPreferredProvider)
    }

    @Test
    fun testGeneralChatProducesNoUnnecessaryCapabilityRequirements() {
        val request = ProviderRequest(userMessage = "Hello there! How are you doing today?")
        val profile = extractor.extract(request)

        assertEquals(TaskIntent.GENERAL_CHAT, profile.inferredIntent?.primaryIntent)
        assertFalse(profile.requiresToolCalling)
        assertFalse(profile.requiresVision)
        assertFalse(profile.requiresWebSearch)
        assertFalse(profile.requiresLongContext)
    }

    @Test
    fun testUnknownProducesConservativeProfile() {
        val request = ProviderRequest(userMessage = "maybe...")
        val profile = extractor.extract(request)

        assertEquals(TaskIntent.UNKNOWN, profile.inferredIntent?.primaryIntent)
        assertFalse(profile.requiresToolCalling)
        assertFalse(profile.requiresVision)
        assertFalse(profile.requiresWebSearch)
        assertFalse(profile.requiresLongContext)
    }

    @Test
    fun testExistingLongContextCalculationRemainsCorrect() {
        val shortRequest = ProviderRequest(userMessage = "X".repeat(1000))
        val shortProfile = extractor.extract(shortRequest)
        assertFalse(shortProfile.requiresLongContext)

        val longRequest = ProviderRequest(userMessage = "X".repeat(35000))
        val longProfile = extractor.extract(longRequest)
        assertTrue(longProfile.requiresLongContext)
        assertEquals(8750, longProfile.estimatedInputTokens)
    }

    @Test
    fun testManualProviderSelectionRemainsUnaffected() {
        val manager = createTestSelectionManager()
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.OPENROUTER, isIncognito = false)

        val request = ProviderRequest(
            userMessage = "Compute this math problem",
            requiresToolCalling = true
        )

        val resolved = manager.resolveProvider(request, isIncognito = false)
        // Manual mode strictly honors OpenRouter regardless of requirements or inferred intents
        assertEquals(AIProviderType.OPENROUTER, resolved)
    }

    @Test
    fun testNoRegressionInExistingRoutingBehavior() {
        val manager = createTestSelectionManager()
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        // Tool calling request routes to Gemini (only Gemini has tool calling capability)
        val toolRequest = ProviderRequest(userMessage = "Run calculation tool", requiresToolCalling = true)
        val resolvedTool = manager.resolveProvider(toolRequest, isIncognito = false)
        assertEquals(AIProviderType.GEMINI, resolvedTool)

        // Vision request routes to null/fallback because active providers do not support vision
        val visionRequest = ProviderRequest(userMessage = "What's in this image?", hasVisionInput = true)
        val resolvedVision = manager.resolveProvider(visionRequest, isIncognito = false)
        assertNull(resolvedVision)
        assertTrue(manager.lastRoutingDecision!!.isFallbackRequired)

        // Simple chat query routes deterministically to an eligible active provider
        val simpleRequest = ProviderRequest(userMessage = "Hi there!")
        val resolvedSimple = manager.resolveProvider(simpleRequest, isIncognito = false)
        assertNotNull(resolvedSimple)
        assertFalse(manager.lastRoutingDecision!!.isFallbackRequired)
    }
}
