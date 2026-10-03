package com.example.ai.provider.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.TimeProvider
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderFactory
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.provider.selection.SelectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveAutoRoutingIntegrationTest {

    private lateinit var context: Context

    class FakeProvider(
        override val type: AIProviderType,
        override val providerName: String,
        private val chunksToEmit: List<String> = listOf("response from $providerName"),
        private val shouldFail: Boolean = false,
        private val isRecoverable: Boolean = true
    ) : AIProvider {
        var generateStreamCalled = false

        override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
            generateStreamCalled = true
            if (shouldFail) {
                if (isRecoverable) {
                    throw ProviderException.NetworkError("Network error on $providerName")
                } else {
                    throw ProviderException.AuthenticationError("Auth error on $providerName")
                }
            }
            chunksToEmit.forEach { text ->
                emit(ProviderStreamChunk(textDelta = text, isComplete = false, providerName = providerName, accumulatedText = text))
            }
            emit(ProviderStreamChunk(textDelta = "", isComplete = true, providerName = providerName, accumulatedText = ""))
        }

        override suspend fun generate(request: ProviderRequest): ProviderResponse {
            if (shouldFail) throw ProviderException.NetworkError("Error")
            return ProviderResponse("Text", providerName)
        }
    }

    class FakeTimeProvider(var currentTime: Long = 1000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        override fun getZoneId(): String = "UTC"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAutoModeActuallyUsesAutoRoutingEngine() {
        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean =
                type in listOf(AIProviderType.GEMINI, AIProviderType.GROQ, AIProviderType.OPENROUTER)
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        // 1. Tool-requiring request (requiresToolCalling = true) routes to GEMINI because Groq lacks tool calling
        val toolRequest = ProviderRequest(userMessage = "summarize", requiresToolCalling = true)
        val resolvedGemini = manager.resolveProvider(toolRequest, isIncognito = false)
        assertEquals(AIProviderType.GEMINI, resolvedGemini)
        assertNotNull(manager.lastRoutingDecision)
        assertEquals(AIProviderType.GEMINI, manager.lastRoutingDecision?.selectedProvider)

        // 2. Simple query routes to GROQ based on performance signal
        val simpleRequest = ProviderRequest(userMessage = "hi")
        val resolvedGroq = manager.resolveProvider(simpleRequest, isIncognito = false)
        assertEquals(AIProviderType.GROQ, resolvedGroq)
        assertNotNull(manager.lastRoutingDecision)
        assertEquals(AIProviderType.GROQ, manager.lastRoutingDecision?.selectedProvider)
    }

    @Test
    fun testSelectedEligibleProviderIsUsed() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("Gemini answer"))
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", listOf("Groq answer"))

        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean =
                type == AIProviderType.GEMINI || type == AIProviderType.GROQ
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        // Tool calling request routes to Gemini
        val result = service.generateResponseStream(
            userMessage = "check file",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = "important file data",
            isIncognito = false,
            requiresToolCalling = true
        ).toList()

        assertTrue(geminiFake.generateStreamCalled)
        assertFalse(groqFake.generateStreamCalled)
        assertTrue(result.contains("Gemini answer"))
    }

    @Test
    fun testManualProviderSelectionRemainsStrict() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("Gemini answer"))
        val openRouterFake = FakeProvider(AIProviderType.OPENROUTER, "OpenRouter", listOf("OpenRouter answer"))

        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = true
        }
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.OPENROUTER, isIncognito = false)

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.OPENROUTER to openRouterFake
            )
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        // Request with tool calling (which Auto would route to Gemini) strictly stays with OpenRouter in Manual mode
        val result = service.generateResponseStream(
            userMessage = "check file",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = "important file data",
            isIncognito = false,
            requiresToolCalling = true
        ).toList()

        assertTrue(openRouterFake.generateStreamCalled)
        assertFalse(geminiFake.generateStreamCalled)
        assertTrue(result.contains("OpenRouter answer"))
    }

    @Test
    fun testInactiveOpenAiAndClaudeCannotBeSelected() {
        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = true
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val req = ProviderRequest(userMessage = "hello")
        val resolved = manager.resolveProvider(req, isIncognito = false)

        assertNotNull(resolved)
        assertTrue(resolved != AIProviderType.OPENAI)
        assertTrue(resolved != AIProviderType.CLAUDE)

        val openAiCandidate = manager.lastRoutingDecision?.candidates?.find { it.providerType == AIProviderType.OPENAI }
        val claudeCandidate = manager.lastRoutingDecision?.candidates?.find { it.providerType == AIProviderType.CLAUDE }
        assertFalse(openAiCandidate!!.eligible)
        assertFalse(claudeCandidate!!.eligible)
    }

    @Test
    fun testNoEligibleAutoDecisionHandledSafely() = runBlocking {
        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = false // None available
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val factory = AIProviderFactory()
        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream(
                userMessage = "hello",
                history = emptyList(),
                relevantMemories = emptyList(),
                fileContext = null,
                isIncognito = false
            ).toList()
            fail("Expected ProviderUnavailableError when no providers are eligible")
        } catch (e: ProviderException.ProviderUnavailableError) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("No eligible providers"))
        }
    }

    @Test
    fun testSuccessfulExecutionUpdatesHealthAndPerformanceTelemetry() = runBlocking {
        val timeProvider = FakeTimeProvider(currentTime = 1000L)
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("answer"))

        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = true
        }
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.GEMINI, isIncognito = false)

        val factory = AIProviderFactory(
            timeProvider = timeProvider,
            providerMap = mapOf(AIProviderType.GEMINI to geminiFake)
        )

        val service = GeminiAssistantService(
            timeProvider = timeProvider,
            selectionManager = manager,
            providerFactory = factory
        )

        timeProvider.currentTime = 1250L // 250ms duration
        service.generateResponseStream(
            userMessage = "hi",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false
        ).toList()

        // Check health tracker updated
        val health = manager.healthTracker.getHealth(AIProviderType.GEMINI)
        assertEquals(com.example.ai.provider.health.ProviderHealthState.HEALTHY, health.healthState)
        assertEquals(0, health.consecutiveFailures)

        // Check performance tracker updated
        val perf = manager.performanceTracker.getPerformance(AIProviderType.GEMINI)
        assertTrue(perf.successfulRequestCount >= 1)
        assertNotNull(perf.lastRequestLatencyMs)
    }

    @Test
    fun testFailedExecutionUpdatesHealthTelemetry() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFail = true, isRecoverable = true)

        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = true
        }
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = false)
        manager.setSelectedProvider(AIProviderType.GEMINI, isIncognito = false)

        val factory = AIProviderFactory(
            providerMap = mapOf(AIProviderType.GEMINI to geminiFake)
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream(
                userMessage = "hi",
                history = emptyList(),
                relevantMemories = emptyList(),
                fileContext = null,
                isIncognito = false
            ).toList()
            fail("Expected exception on failed provider")
        } catch (e: ProviderException.NetworkError) {
            // Expected
        }

        // Verify failure was recorded
        val health = manager.healthTracker.getHealth(AIProviderType.GEMINI)
        assertTrue(health.consecutiveFailures >= 1)

        val perf = manager.performanceTracker.getPerformance(AIProviderType.GEMINI)
        assertTrue(perf.failedRequestCount >= 1)
    }

    @Test
    fun testIncognitoDoesNotPersistRoutingOrTelemetryState() = runBlocking {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("Incognito answer"))
        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = true
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = true)

        val factory = AIProviderFactory(
            providerMap = mapOf(AIProviderType.GEMINI to geminiFake)
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        service.generateResponseStream(
            userMessage = "test",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = "file data",
            isIncognito = true
        ).toList()

        // Verify SharedPreferences has zero keys
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun testExistingFallbackBehaviorRemainsBounded() = runBlocking {
        // Groq fails recoverably, Gemini succeeds as fallback
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", shouldFail = true, isRecoverable = true)
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("Fallback Gemini answer"))

        val manager = object : ModelSelectionManager(context) {
            override fun isProviderAvailable(type: AIProviderType): Boolean =
                type in listOf(AIProviderType.GROQ, AIProviderType.GEMINI)
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GROQ to groqFake,
                AIProviderType.GEMINI to geminiFake
            )
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        // Simple query resolves to Groq as primary, falls back to Gemini
        val result = service.generateResponseStream(
            userMessage = "hi",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false
        ).toList()

        assertTrue(result.contains("Fallback Gemini answer"))
        assertTrue(groqFake.generateStreamCalled)
        assertTrue(geminiFake.generateStreamCalled)

        // Verify Groq failure telemetry and Gemini success telemetry
        assertTrue(manager.healthTracker.getHealth(AIProviderType.GROQ).consecutiveFailures >= 1)
        assertEquals(0, manager.healthTracker.getHealth(AIProviderType.GEMINI).consecutiveFailures)
    }
}
