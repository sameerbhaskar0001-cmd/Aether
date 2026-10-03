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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoRoutingRefinementTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun createManager(
        availableProviders: List<AIProviderType> = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
    ): ModelSelectionManager {
        return object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = type in availableProviders
        }
    }

    @Test
    fun testPersistedUnavailableProviderDefaultsToGemini() {
        val manager = createManager(availableProviders = listOf(AIProviderType.GEMINI)) // OpenRouter is unavailable
        manager.setSelectedProvider(AIProviderType.OPENROUTER, isIncognito = false)

        // getSelectedProvider must safely fallback to GEMINI when the persisted provider is unavailable
        val activeProvider = manager.getSelectedProvider(isIncognito = false)
        assertEquals(AIProviderType.GEMINI, activeProvider)
    }

    @Test
    fun testAutoModeIgnoresPersistedUnavailableManualSelection() {
        val manager = createManager(availableProviders = listOf(AIProviderType.GEMINI, AIProviderType.GROQ))
        manager.setSelectedProvider(AIProviderType.OPENROUTER, isIncognito = false)
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val request = ProviderRequest(userMessage = "hello")
        val resolved = manager.resolveProvider(request, isIncognito = false)

        // Auto mode must route to an active/available provider and ignore unconfigured persisted manual selection
        assertNotNull(resolved)
        assertTrue(resolved in listOf(AIProviderType.GEMINI, AIProviderType.GROQ))
        assertFalse(manager.lastRoutingDecision!!.isFallbackRequired)
    }

    @Test
    fun testAmbiguousTaskRemainsRoutable() {
        val manager = createManager()
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val request = ProviderRequest(userMessage = "hm ok")
        val profile = manager.buildRoutingTaskProfile(request)

        assertEquals(TaskIntent.UNKNOWN, profile.inferredIntent?.primaryIntent)
        assertFalse(profile.requiresVision)
        assertFalse(profile.requiresWebSearch)
        assertFalse(profile.requiresToolCalling)

        val resolved = manager.resolveProvider(request, isIncognito = false)
        assertNotNull(resolved)
    }

    @Test
    fun testLowConfidenceIntentDoesNotForceCapability() {
        val manager = createManager()
        val request = ProviderRequest(userMessage = "maybe picture somewhere")
        val profile = manager.buildRoutingTaskProfile(request)

        // Low-confidence or unflagged intent must not force vision requirement
        assertFalse(profile.requiresVision)
        assertFalse(profile.requiresWebSearch)
    }

    @Test
    fun testExplicitFlagsRemainAuthoritativeOverInferredIntent() {
        val manager = createManager()
        val request = ProviderRequest(
            userMessage = "Write a simple function",
            hasVisionInput = true,
            requiresWebSearch = true
        )
        val profile = manager.buildRoutingTaskProfile(request)

        assertTrue(profile.requiresVision)
        assertTrue(profile.requiresWebSearch)
    }

    @Test
    fun testConflictingSignalsHandledSafely() {
        val manager = createManager()
        val request = ProviderRequest(
            userMessage = "Process this file and run calculation",
            fileContext = "data snippet",
            requiresToolCalling = true
        )
        val profile = manager.buildRoutingTaskProfile(request)

        // File context is captured without forcing tool calling on its own, but explicit flag is respected
        assertEquals(TaskIntent.FILE_ANALYSIS, profile.inferredIntent?.primaryIntent)
        assertTrue(profile.requiresToolCalling)
    }

    @Test
    fun testManualModeRemainsStrict() {
        val manager = createManager(availableProviders = listOf(AIProviderType.GEMINI, AIProviderType.GROQ))
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.GROQ, isIncognito = false)

        val request = ProviderRequest(userMessage = "Hello")
        val resolved = manager.resolveProvider(request, isIncognito = false)

        assertEquals(AIProviderType.GROQ, resolved)
    }
}
