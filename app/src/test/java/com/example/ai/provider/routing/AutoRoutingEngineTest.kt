package com.example.ai.provider.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.cost.CostQuotaInfo
import com.example.ai.provider.cost.CostQuotaRegistry
import com.example.ai.provider.health.ProviderHealthTracker
import com.example.ai.provider.metadata.AIProviderMetadataRegistry
import com.example.ai.provider.performance.ProviderPerformanceTracker
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
class AutoRoutingEngineTest {

    private lateinit var context: Context
    private lateinit var metadataRegistry: AIProviderMetadataRegistry
    private lateinit var healthTracker: ProviderHealthTracker
    private lateinit var performanceTracker: ProviderPerformanceTracker
    private lateinit var costQuotaRegistry: CostQuotaRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Default test setup: Gemini, Groq, OpenRouter configured; OpenAI & Claude inactive
        metadataRegistry = AIProviderMetadataRegistry(
            isKeyConfigured = { it in listOf(AIProviderType.GEMINI, AIProviderType.GROQ, AIProviderType.OPENROUTER) }
        )
        healthTracker = ProviderHealthTracker()
        performanceTracker = ProviderPerformanceTracker()
        costQuotaRegistry = CostQuotaRegistry()
    }

    private fun createEngine(): AutoRoutingEngine {
        return AutoRoutingEngine(
            metadataRegistry = metadataRegistry,
            healthTracker = healthTracker,
            performanceTracker = performanceTracker,
            costQuotaRegistry = costQuotaRegistry
        )
    }

    @Test
    fun testEligibleConfiguredProviderCanBeSelected() {
        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile())

        assertNotNull(decision.selectedProvider)
        assertFalse(decision.isFallbackRequired)

        val candidate = decision.candidates.find { it.providerType == decision.selectedProvider }
        assertNotNull(candidate)
        assertTrue(candidate!!.eligible)
        assertTrue(candidate.score > 0.0)
    }

    @Test
    fun testInactiveOpenAiAndClaudeNeverSelected() {
        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile())

        val openAiCandidate = decision.candidates.find { it.providerType == AIProviderType.OPENAI }
        val claudeCandidate = decision.candidates.find { it.providerType == AIProviderType.CLAUDE }

        assertNotNull(openAiCandidate)
        assertFalse(openAiCandidate!!.eligible)
        assertEquals("Provider is inactive in this release", openAiCandidate.exclusionReason)

        assertNotNull(claudeCandidate)
        assertFalse(claudeCandidate!!.eligible)
        assertEquals("Provider is inactive in this release", claudeCandidate.exclusionReason)

        assertTrue(decision.selectedProvider != AIProviderType.OPENAI)
        assertTrue(decision.selectedProvider != AIProviderType.CLAUDE)
    }

    @Test
    fun testUnsupportedRequiredCapabilityExcludesProvider() {
        val engine = createEngine()
        // Tool calling is supported only on Gemini, not Groq or OpenRouter
        val taskProfile = RoutingTaskProfile(requiresToolCalling = true)
        val decision = engine.evaluate(taskProfile)

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
    fun testUnknownRequiredCapabilityDoesNotQualifyProvider() {
        val engine = createEngine()
        // Vision is UNKNOWN on Gemini, and UNSUPPORTED on Groq/OpenRouter
        val taskProfile = RoutingTaskProfile(requiresVision = true)
        val decision = engine.evaluate(taskProfile)

        // UNKNOWN vision must NOT qualify Gemini
        val geminiCandidate = decision.candidates.find { it.providerType == AIProviderType.GEMINI }
        assertNotNull(geminiCandidate)
        assertFalse(geminiCandidate!!.eligible)
        assertEquals("Vision required but not supported or unknown", geminiCandidate.exclusionReason)

        // None are eligible
        assertNull(decision.selectedProvider)
        assertTrue(decision.isFallbackRequired)
    }

    @Test
    fun testInsufficientKnownContextLimitExcludesProvider() {
        val engine = createEngine()
        // Groq context limit is 8192 tokens. 12000 tokens should exclude Groq
        val taskProfile = RoutingTaskProfile(estimatedInputTokens = 12000)
        val decision = engine.evaluate(taskProfile)

        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }
        assertNotNull(groqCandidate)
        assertFalse(groqCandidate!!.eligible)
        assertTrue(groqCandidate.exclusionReason!!.contains("exceeds context limit"))

        // Gemini (1048576) and OpenRouter (32768) remain eligible
        val geminiCandidate = decision.candidates.find { it.providerType == AIProviderType.GEMINI }
        val openRouterCandidate = decision.candidates.find { it.providerType == AIProviderType.OPENROUTER }
        assertTrue(geminiCandidate!!.eligible)
        assertTrue(openRouterCandidate!!.eligible)
    }

    @Test
    fun testUnhealthyDegradedProviderAffectsEligibilityAndScoreCorrectly() {
        // Record 3 failures on Gemini to transition it to DEGRADED
        healthTracker.recordFailure(AIProviderType.GEMINI)
        healthTracker.recordFailure(AIProviderType.GEMINI)
        healthTracker.recordFailure(AIProviderType.GEMINI)

        // Groq is HEALTHY
        healthTracker.recordSuccess(AIProviderType.GROQ)

        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile())

        val geminiCandidate = decision.candidates.find { it.providerType == AIProviderType.GEMINI }!!
        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }!!

        assertTrue(groqCandidate.healthFit > geminiCandidate.healthFit)
        assertTrue(groqCandidate.score > geminiCandidate.score)
        assertEquals(AIProviderType.GROQ, decision.selectedProvider)
    }

    @Test
    fun testPerformanceSignalAffectsRankingDeterministically() {
        // Both healthy
        healthTracker.recordSuccess(AIProviderType.GEMINI)
        healthTracker.recordSuccess(AIProviderType.GROQ)

        // Gemini is fast (120ms), Groq is slower (1800ms)
        performanceTracker.recordRequestSuccess(AIProviderType.GEMINI, 120L)
        performanceTracker.recordRequestSuccess(AIProviderType.GROQ, 1800L)

        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile())

        val geminiCandidate = decision.candidates.find { it.providerType == AIProviderType.GEMINI }!!
        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }!!

        assertTrue(geminiCandidate.performanceFit > groqCandidate.performanceFit)
        assertTrue(geminiCandidate.score > groqCandidate.score)
        assertEquals(AIProviderType.GEMINI, decision.selectedProvider)
    }

    @Test
    fun testKnownCostQuotaSignalAffectsRankingDeterministically() {
        // Both healthy, neutral performance
        healthTracker.recordSuccess(AIProviderType.GROQ)
        healthTracker.recordSuccess(AIProviderType.OPENROUTER)

        // GROQ has lower cost than OPENROUTER
        costQuotaRegistry.setCostQuota(CostQuotaInfo(AIProviderType.GROQ, costKnown = true, estimatedCost = 0.0001))
        costQuotaRegistry.setCostQuota(CostQuotaInfo(AIProviderType.OPENROUTER, costKnown = true, estimatedCost = 0.05))

        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile())

        val groqCandidate = decision.candidates.find { it.providerType == AIProviderType.GROQ }!!
        val openRouterCandidate = decision.candidates.find { it.providerType == AIProviderType.OPENROUTER }!!

        assertTrue(groqCandidate.costFit > openRouterCandidate.costFit)
    }

    @Test
    fun testNoEligibleProviderReturnsNullDecision() {
        // Require vision (unsupported across all active providers)
        val engine = createEngine()
        val decision = engine.evaluate(RoutingTaskProfile(requiresVision = true))

        assertNull(decision.selectedProvider)
        assertTrue(decision.isFallbackRequired)
        assertTrue(decision.candidates.none { it.eligible })
        assertTrue(decision.reason.contains("No eligible providers available"))
    }

    @Test
    fun testProviderCandidatesAreIndependentlyEvaluated() {
        val engine = createEngine()

        val baselineDecision = engine.evaluate(RoutingTaskProfile())
        val baselineGeminiScore = baselineDecision.candidates.find { it.providerType == AIProviderType.GEMINI }!!.score

        // Modifying Groq's performance does not change Gemini's score
        performanceTracker.recordRequestSuccess(AIProviderType.GROQ, 50L)
        val updatedDecision = engine.evaluate(RoutingTaskProfile())
        val updatedGeminiScore = updatedDecision.candidates.find { it.providerType == AIProviderType.GEMINI }!!.score

        assertEquals(baselineGeminiScore, updatedGeminiScore, 0.0001)
    }

    @Test
    fun testSameInputsAlwaysProduceSameDecision() {
        val engine = createEngine()
        val profile = RoutingTaskProfile(requiresWebSearch = true)

        val decision1 = engine.evaluate(profile)
        val decision2 = engine.evaluate(profile)

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
    fun testNoPersistenceIsIntroduced() {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val engine = createEngine()
        engine.evaluate(RoutingTaskProfile(requiresToolCalling = true))
        engine.evaluate(RoutingTaskProfile(requiresWebSearch = true))

        assertTrue(prefs.all.isEmpty())
    }
}
