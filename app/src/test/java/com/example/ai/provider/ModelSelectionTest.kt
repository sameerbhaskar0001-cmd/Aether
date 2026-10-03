package com.example.ai.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.provider.selection.SelectionMode
import com.example.ai.provider.models.ProviderRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelSelectionTest {

    private lateinit var context: Context
    private lateinit var manager: ModelSelectionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = ModelSelectionManager(context)

        // Clear shared preferences before each test
        context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testDefaultModeIsAuto() {
        assertEquals(SelectionMode.AUTO, manager.getSelectionMode(isIncognito = false))
        assertEquals(AIProviderType.GEMINI, manager.getSelectedProvider(isIncognito = false))
    }

    @Test
    fun testManualSelectionPrecedence() {
        // Set to Manual with GROQ (assuming GROQ is mocked as available for routing)
        val mockManager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean {
                return type == AIProviderType.GROQ || type == AIProviderType.GEMINI
            }
        }

        mockManager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        mockManager.setSelectedProvider(AIProviderType.GROQ, isIncognito = false)

        val request = ProviderRequest(userMessage = "simple math")
        val resolved = mockManager.resolveProvider(request, isIncognito = false)

        assertEquals(AIProviderType.GROQ, resolved)
    }

    @Test
    fun testAutoSelectionPolicyComplexRequest() {
        val mockManager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean {
                return true // All available
            }
        }

        mockManager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        // 1. A request requiring tool calling should resolve to GEMINI
        val complexRequest = ProviderRequest(
            userMessage = "query",
            requiresToolCalling = true
        )
        val resolvedComplex = mockManager.resolveProvider(complexRequest, isIncognito = false)
        assertEquals(AIProviderType.GEMINI, resolvedComplex)

        // 2. A long context message (>8000 tokens, 35000 chars) should resolve to GEMINI
        val longMessageRequest = ProviderRequest(
            userMessage = "a".repeat(35000)
        )
        val resolvedLong = mockManager.resolveProvider(longMessageRequest, isIncognito = false)
        assertEquals(AIProviderType.GEMINI, resolvedLong)
    }

    @Test
    fun testAutoSelectionPolicySimpleRequest() {
        val mockManager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean {
                return type == AIProviderType.GROQ || type == AIProviderType.GEMINI
            }
        }

        mockManager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        // A simple query with Groq available should route to GROQ for speed
        val simpleRequest = ProviderRequest(userMessage = "hi")
        val resolvedSimple = mockManager.resolveProvider(simpleRequest, isIncognito = false)
        assertEquals(AIProviderType.GROQ, resolvedSimple)
    }

    @Test
    fun testIncognitoIsolationNonPersistence() {
        // 1. Normal mode saves to disk
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.OPENROUTER, isIncognito = false)

        // 2. Incognito mode saves to memory only
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = true)
        manager.setSelectedProvider(AIProviderType.GROQ, isIncognito = true)

        // 3. Verify Incognito mode returns in-memory values
        assertEquals(SelectionMode.AUTO, manager.getSelectionMode(isIncognito = true))
        assertEquals(AIProviderType.GROQ, manager.getSelectedProvider(isIncognito = true))

        // 4. Verify Normal mode values are unaffected on disk
        assertEquals(SelectionMode.MANUAL, manager.getSelectionMode(isIncognito = false))
        assertEquals(AIProviderType.OPENROUTER, manager.getSelectedProvider(isIncognito = false))

        // 5. Re-instantiate manager to verify disk persistence of normal mode only
        val freshManager = ModelSelectionManager(context)
        assertEquals(SelectionMode.MANUAL, freshManager.getSelectionMode(isIncognito = false))
        assertEquals(AIProviderType.OPENROUTER, freshManager.getSelectedProvider(isIncognito = false))

        // 6. Verify that freshManager has lost in-memory incognito values (confirming non-persistence)
        assertEquals(SelectionMode.AUTO, freshManager.getSelectionMode(isIncognito = true))
        assertEquals(AIProviderType.GEMINI, freshManager.getSelectedProvider(isIncognito = true))
    }

    @Test
    fun testUnavailableProviderExcludedFromAuto() {
        val mockManager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean {
                // Only Gemini is available
                return type == AIProviderType.GEMINI
            }
        }

        mockManager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)
        val simpleRequest = ProviderRequest(userMessage = "test query")
        val resolved = mockManager.resolveProvider(simpleRequest, isIncognito = false)

        // Should resolve to Gemini since GROQ is unavailable
        assertEquals(AIProviderType.GEMINI, resolved)
    }
}
