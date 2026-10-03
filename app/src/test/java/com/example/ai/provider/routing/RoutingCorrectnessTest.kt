package com.example.ai.provider.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.TimeProvider
import com.example.ai.provider.AIProvider
import com.example.ai.provider.AIProviderFactory
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderRole
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.provider.selection.SelectionMode
import com.example.data.model.Memory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
class RoutingCorrectnessTest {

    private lateinit var context: Context

    class FakeProvider(
        override val type: AIProviderType,
        override val providerName: String,
        private val chunksToEmit: List<String> = listOf("response from $providerName"),
        private val shouldFail: Boolean = false,
        private val isRecoverable: Boolean = true
    ) : AIProvider {
        var generateStreamCalls = 0

        override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
            generateStreamCalls++
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

    private fun createTestSelectionManager(
        availableTypes: List<AIProviderType> = listOf(AIProviderType.GEMINI, AIProviderType.GROQ, AIProviderType.OPENROUTER),
        performanceTracker: com.example.ai.provider.performance.ProviderPerformanceTracker = com.example.ai.provider.performance.ProviderPerformanceTracker()
    ): ModelSelectionManager {
        return object : ModelSelectionManager(context, performanceTracker = performanceTracker) {
            override fun isProviderAvailable(type: AIProviderType): Boolean = type in availableTypes
        }
    }

    @Test
    fun testFileContextDoesNotImplyToolCalling() {
        val manager = createTestSelectionManager()
        val request = ProviderRequest(
            userMessage = "Here is the log content to analyze",
            fileContext = "2026-10-02 ERROR NetworkTimeout occurred in module A",
            requiresToolCalling = false
        )

        val profile = manager.buildRoutingTaskProfile(request)
        // File context must NOT automatically set requiresToolCalling = true
        assertFalse(profile.requiresToolCalling)

        // Since tool calling is NOT required, Groq should NOT be excluded due to missing tool calling
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)
        val decision = manager.routingEngine.evaluate(profile)
        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }
        assertNotNull(groqCandidate)
        assertTrue(groqCandidate!!.eligible)
    }

    @Test
    fun testMemoryContextDoesNotImplyToolCalling() {
        val manager = createTestSelectionManager()
        val memory = Memory(
            id = "mem-1",
            content = "User prefers concise answers in Kotlin",
            category = "preference",
            createdTimestamp = 1000L,
            lastUpdatedTimestamp = 1000L,
            sourceConversationId = null
        )
        val request = ProviderRequest(
            userMessage = "Explain coroutines",
            relevantMemories = listOf(memory),
            requiresToolCalling = false
        )

        val profile = manager.buildRoutingTaskProfile(request)
        // Memory context must NOT set requiresToolCalling = true
        assertFalse(profile.requiresToolCalling)

        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)
        val decision = manager.routingEngine.evaluate(profile)
        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }
        assertNotNull(groqCandidate)
        assertTrue(groqCandidate!!.eligible)
    }

    @Test
    fun testExplicitStructuredToolRequirementDoesImplyToolCalling() {
        val manager = createTestSelectionManager()
        val request = ProviderRequest(
            userMessage = "Run calculation tool",
            requiresToolCalling = true
        )

        val profile = manager.buildRoutingTaskProfile(request)
        assertTrue(profile.requiresToolCalling)

        val decision = manager.routingEngine.evaluate(profile)
        // Gemini supports tool calling, Groq and OpenRouter do not
        assertEquals(AIProviderType.GEMINI, decision.selectedProvider)

        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }
        assertNotNull(groqCandidate)
        assertFalse(groqCandidate!!.eligible)
        assertEquals("Tool calling required but not supported", groqCandidate.exclusionReason)

        val openRouterCandidate = decision.candidates.find { it.providerType == AIProviderType.OPENROUTER }
        assertNotNull(openRouterCandidate)
        assertFalse(openRouterCandidate!!.eligible)
        assertEquals("Tool calling required but not supported", openRouterCandidate.exclusionReason)
    }

    @Test
    fun testVisionRequirementRemainsFalseWithoutActualVisionInput() {
        val manager = createTestSelectionManager()
        val requestWithoutVision = ProviderRequest(
            userMessage = "What is the capital of France?",
            hasVisionInput = false
        )
        val profileWithoutVision = manager.buildRoutingTaskProfile(requestWithoutVision)
        assertFalse(profileWithoutVision.requiresVision)

        val requestWithVision = ProviderRequest(
            userMessage = "Describe this diagram",
            hasVisionInput = true
        )
        val profileWithVision = manager.buildRoutingTaskProfile(requestWithVision)
        assertTrue(profileWithVision.requiresVision)
    }

    @Test
    fun testWebSearchRequirementRemainsFalseWithoutExplicitSearchSignal() {
        val manager = createTestSelectionManager()
        val requestWithoutSearch = ProviderRequest(
            userMessage = "Write a python function",
            requiresWebSearch = false
        )
        val profileWithoutSearch = manager.buildRoutingTaskProfile(requestWithoutSearch)
        assertFalse(profileWithoutSearch.requiresWebSearch)

        val requestWithSearch = ProviderRequest(
            userMessage = "What is today's stock price for GOOG?",
            requiresWebSearch = true
        )
        val profileWithSearch = manager.buildRoutingTaskProfile(requestWithSearch)
        assertTrue(profileWithSearch.requiresWebSearch)
    }

    @Test
    fun testLongContextDetectionRemainsDeterministic() {
        val manager = createTestSelectionManager()

        // Short request (~25 estimated tokens)
        val shortRequest = ProviderRequest(
            userMessage = "A".repeat(100)
        )
        val shortProfile = manager.buildRoutingTaskProfile(shortRequest)
        assertEquals(25, shortProfile.estimatedInputTokens)
        assertFalse(shortProfile.requiresLongContext)

        // Long request (~10,000 estimated tokens)
        val longRequest = ProviderRequest(
            userMessage = "A".repeat(40000)
        )
        val longProfile = manager.buildRoutingTaskProfile(longRequest)
        assertEquals(10000, longProfile.estimatedInputTokens)
        assertTrue(longProfile.requiresLongContext)

        // Multiple evaluations of identical request must produce exact same profile
        val rerunProfile = manager.buildRoutingTaskProfile(longRequest)
        assertEquals(longProfile.estimatedInputTokens, rerunProfile.estimatedInputTokens)
        assertEquals(longProfile.requiresLongContext, rerunProfile.requiresLongContext)
    }

    @Test
    fun testRequiredUnknownCapabilityExcludesProvider() {
        val manager = createTestSelectionManager()
        // Vision is UNKNOWN on Gemini, UNSUPPORTED on Groq and OpenRouter
        val requestWithVision = ProviderRequest(
            userMessage = "Analyze screenshot",
            hasVisionInput = true
        )
        val profile = manager.buildRoutingTaskProfile(requestWithVision)
        val decision = manager.routingEngine.evaluate(profile)

        // UNKNOWN capability must NEVER satisfy required capability
        assertNull(decision.selectedProvider)
        assertTrue(decision.isFallbackRequired)

        val geminiCandidate = decision.candidates.find { it.providerType == AIProviderType.GEMINI }
        assertNotNull(geminiCandidate)
        assertFalse(geminiCandidate!!.eligible)
        assertEquals("Vision required but not supported or unknown", geminiCandidate.exclusionReason)
    }

    @Test
    fun testManualSelectionRemainsStrict() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini")
        val openRouterFake = FakeProvider(AIProviderType.OPENROUTER, "OpenRouter", listOf("Strict OpenRouter response"))

        val manager = createTestSelectionManager()
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

        // Even with an explicit tool requirement (which OpenRouter does NOT support), Manual mode must strictly stay with OpenRouter
        val result = service.generateResponseStream(
            userMessage = "use tool",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false,
            requiresToolCalling = true
        ).toList()

        assertEquals(1, openRouterFake.generateStreamCalls)
        assertEquals(0, geminiFake.generateStreamCalls)
        assertTrue(result.contains("Strict OpenRouter response"))
    }

    @Test
    fun testAutoSelectionRemainsBoundedAndDeterministic() {
        val manager = createTestSelectionManager()
        val request = ProviderRequest(userMessage = "hello")
        val profile = manager.buildRoutingTaskProfile(request)

        val decision1 = manager.routingEngine.evaluate(profile)
        val decision2 = manager.routingEngine.evaluate(profile)

        assertEquals(decision1.selectedProvider, decision2.selectedProvider)
        assertEquals(decision1.isFallbackRequired, decision2.isFallbackRequired)
        assertEquals(decision1.candidates.size, decision2.candidates.size)

        for (i in decision1.candidates.indices) {
            val c1 = decision1.candidates[i]
            val c2 = decision2.candidates[i]
            assertEquals(c1.providerType, c2.providerType)
            assertEquals(c1.eligible, c2.eligible)
            assertEquals(c1.score, c2.score, 0.0001)
        }
    }

    @Test
    fun testTelemetryIsNotDuplicated() = runBlocking {
        val timeProvider = FakeTimeProvider(currentTime = 1000L)
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("single response"))

        val manager = createTestSelectionManager()
        manager.performanceTracker.resetAll()
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

        timeProvider.currentTime = 1150L // 150ms
        service.generateResponseStream(
            userMessage = "test",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false
        ).toList()

        // Verify exactly one success recorded
        val perf = manager.performanceTracker.getPerformance(AIProviderType.GEMINI)
        assertEquals(1, perf.successfulRequestCount)
        assertEquals(0, perf.failedRequestCount)
        assertNotNull(perf.lastRequestLatencyMs)

        val health = manager.healthTracker.getHealth(AIProviderType.GEMINI)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(com.example.ai.provider.health.ProviderHealthState.HEALTHY, health.healthState)
    }

    @Test
    fun testFallbackTelemetryBelongsToActualAttemptedProviders() = runBlocking {
        val timeProvider = FakeTimeProvider(currentTime = 1000L)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", shouldFail = true, isRecoverable = true)
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("fallback answer"))

        val cleanPerfTracker = com.example.ai.provider.performance.ProviderPerformanceTracker()
        val costRegistry = com.example.ai.provider.cost.CostQuotaRegistry().apply {
            setCostQuota(com.example.ai.provider.cost.CostQuotaInfo(AIProviderType.GROQ, costKnown = true, estimatedCost = 0.0001))
            setCostQuota(com.example.ai.provider.cost.CostQuotaInfo(AIProviderType.GEMINI, costKnown = true, estimatedCost = 0.05))
        }

        val manager = object : ModelSelectionManager(
            context,
            performanceTracker = cleanPerfTracker,
            costQuotaRegistry = costRegistry
        ) {
            override fun isProviderAvailable(type: AIProviderType): Boolean =
                type in listOf(AIProviderType.GROQ, AIProviderType.GEMINI)
        }
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = false)

        val factory = AIProviderFactory(
            timeProvider = timeProvider,
            providerMap = mapOf(
                AIProviderType.GROQ to groqFake,
                AIProviderType.GEMINI to geminiFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = timeProvider,
            selectionManager = manager,
            providerFactory = factory
        )

        // Groq fails at 1100L (100ms duration), Gemini succeeds at 1350L (250ms duration)
        timeProvider.currentTime = 1000L
        val result = service.generateResponseStream(
            userMessage = "test",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = null,
            isIncognito = false
        ).toList()

        assertTrue(result.contains("fallback answer"))

        // Groq had failure: exactly 1 failed request, 0 successful requests, 1 consecutive failure
        val groqPerf = manager.performanceTracker.getPerformance(AIProviderType.GROQ)
        assertEquals(1, groqPerf.failedRequestCount)
        assertEquals(0, groqPerf.successfulRequestCount)
        val groqHealth = manager.healthTracker.getHealth(AIProviderType.GROQ)
        assertEquals(1, groqHealth.consecutiveFailures)

        // Gemini had success: exactly 1 successful request, 0 failed requests, 0 consecutive failures
        val geminiPerf = manager.performanceTracker.getPerformance(AIProviderType.GEMINI)
        assertEquals(1, geminiPerf.successfulRequestCount)
        assertEquals(0, geminiPerf.failedRequestCount)
        val geminiHealth = manager.healthTracker.getHealth(AIProviderType.GEMINI)
        assertEquals(0, geminiHealth.consecutiveFailures)
    }

    @Test
    fun testIncognitoIntroducesNoPersistence() = runBlocking {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", listOf("Incognito response"))
        val manager = createTestSelectionManager()
        manager.setSelectionMode(SelectionMode.AUTO, isIncognito = true)

        val factory = AIProviderFactory(
            providerMap = mapOf(AIProviderType.GEMINI to geminiFake)
        )

        val service = GeminiAssistantService(
            selectionManager = manager,
            providerFactory = factory
        )

        service.generateResponseStream(
            userMessage = "test prompt",
            history = emptyList(),
            relevantMemories = emptyList(),
            fileContext = "test file",
            isIncognito = true
        ).toList()

        // SharedPreferences must remain completely empty
        assertTrue(prefs.all.isEmpty())
    }
}
