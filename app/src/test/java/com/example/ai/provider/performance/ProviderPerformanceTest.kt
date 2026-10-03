package com.example.ai.provider.performance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.cost.CostQuotaInfo
import com.example.ai.provider.cost.CostQuotaRegistry
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
class ProviderPerformanceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testInitialPerformanceState() {
        val tracker = ProviderPerformanceTracker()
        val perf = tracker.getPerformance(AIProviderType.GEMINI)

        assertEquals(AIProviderType.GEMINI, perf.providerType)
        assertNull(perf.lastRequestLatencyMs)
        assertNull(perf.averageLatencyMs)
        assertEquals(0, perf.successfulRequestCount)
        assertEquals(0, perf.failedRequestCount)
    }

    @Test
    fun testSuccessfulLatencyRecording() {
        val tracker = ProviderPerformanceTracker()
        tracker.recordRequestSuccess(AIProviderType.GEMINI, 120L)

        val perf = tracker.getPerformance(AIProviderType.GEMINI)
        assertEquals(120L, perf.lastRequestLatencyMs)
        assertEquals(120L, perf.averageLatencyMs)
        assertEquals(1, perf.successfulRequestCount)
        assertEquals(0, perf.failedRequestCount)

        tracker.recordRequestSuccess(AIProviderType.GEMINI, 200L)
        val perf2 = tracker.getPerformance(AIProviderType.GEMINI)
        assertEquals(200L, perf2.lastRequestLatencyMs)
        assertEquals(160L, perf2.averageLatencyMs) // (120 + 200) / 2
        assertEquals(2, perf2.successfulRequestCount)
        assertEquals(0, perf2.failedRequestCount)
    }

    @Test
    fun testFailedRequestRecording() {
        val tracker = ProviderPerformanceTracker()
        tracker.recordRequestFailure(AIProviderType.GROQ, 250L)

        val perf = tracker.getPerformance(AIProviderType.GROQ)
        assertEquals(250L, perf.lastRequestLatencyMs)
        assertEquals(0, perf.successfulRequestCount)
        assertEquals(1, perf.failedRequestCount)
        assertNull(perf.averageLatencyMs) // no successful requests for average latency
    }

    @Test
    fun testNegativeLatencyIgnored() {
        val tracker = ProviderPerformanceTracker()

        // Negative success latency
        tracker.recordRequestSuccess(AIProviderType.OPENROUTER, -50L)
        val perfSuccess = tracker.getPerformance(AIProviderType.OPENROUTER)
        assertEquals(0, perfSuccess.successfulRequestCount)
        assertNull(perfSuccess.lastRequestLatencyMs)

        // Negative failure latency
        tracker.recordRequestFailure(AIProviderType.OPENROUTER, -100L)
        val perfFailure = tracker.getPerformance(AIProviderType.OPENROUTER)
        assertEquals(0, perfFailure.failedRequestCount)
        assertNull(perfFailure.lastRequestLatencyMs)
    }

    @Test
    fun testBoundedRollingAverageBehavesCorrectly() {
        // Window size of 3
        val tracker = ProviderPerformanceTracker(windowSize = 3)

        tracker.recordRequestSuccess(AIProviderType.GEMINI, 100L)
        assertEquals(100L, tracker.getPerformance(AIProviderType.GEMINI).averageLatencyMs)

        tracker.recordRequestSuccess(AIProviderType.GEMINI, 200L)
        assertEquals(150L, tracker.getPerformance(AIProviderType.GEMINI).averageLatencyMs) // (100 + 200) / 2

        tracker.recordRequestSuccess(AIProviderType.GEMINI, 300L)
        assertEquals(200L, tracker.getPerformance(AIProviderType.GEMINI).averageLatencyMs) // (100 + 200 + 300) / 3

        // 4th request: oldest (100L) evicted from window
        tracker.recordRequestSuccess(AIProviderType.GEMINI, 400L)
        assertEquals(300L, tracker.getPerformance(AIProviderType.GEMINI).averageLatencyMs) // (200 + 300 + 400) / 3

        // 5th request: oldest (200L) evicted from window
        tracker.recordRequestSuccess(AIProviderType.GEMINI, 500L)
        val perf = tracker.getPerformance(AIProviderType.GEMINI)
        assertEquals(400L, perf.averageLatencyMs) // (300 + 400 + 500) / 3
        assertEquals(500L, perf.lastRequestLatencyMs)
        assertEquals(5, perf.successfulRequestCount)
    }

    @Test
    fun testResetClearsPerformanceData() {
        val tracker = ProviderPerformanceTracker()
        tracker.recordRequestSuccess(AIProviderType.GROQ, 180L)
        tracker.recordRequestFailure(AIProviderType.GROQ, 220L)

        val beforeReset = tracker.getPerformance(AIProviderType.GROQ)
        assertEquals(1, beforeReset.successfulRequestCount)
        assertEquals(1, beforeReset.failedRequestCount)

        tracker.reset(AIProviderType.GROQ)
        val afterReset = tracker.getPerformance(AIProviderType.GROQ)
        assertEquals(0, afterReset.successfulRequestCount)
        assertEquals(0, afterReset.failedRequestCount)
        assertNull(afterReset.lastRequestLatencyMs)
        assertNull(afterReset.averageLatencyMs)
    }

    @Test
    fun testProviderMeasurementsRemainIndependent() {
        val tracker = ProviderPerformanceTracker()
        tracker.recordRequestSuccess(AIProviderType.GEMINI, 100L)
        tracker.recordRequestSuccess(AIProviderType.GROQ, 500L)
        tracker.recordRequestFailure(AIProviderType.GROQ)

        val geminiPerf = tracker.getPerformance(AIProviderType.GEMINI)
        val groqPerf = tracker.getPerformance(AIProviderType.GROQ)
        val openRouterPerf = tracker.getPerformance(AIProviderType.OPENROUTER)

        assertEquals(1, geminiPerf.successfulRequestCount)
        assertEquals(0, geminiPerf.failedRequestCount)
        assertEquals(100L, geminiPerf.averageLatencyMs)

        assertEquals(1, groqPerf.successfulRequestCount)
        assertEquals(1, groqPerf.failedRequestCount)
        assertEquals(500L, groqPerf.averageLatencyMs)

        assertEquals(0, openRouterPerf.successfulRequestCount)
        assertEquals(0, openRouterPerf.failedRequestCount)
        assertNull(openRouterPerf.averageLatencyMs)
    }

    @Test
    fun testUnknownCostQuotaRemainsUnknown() {
        val registry = CostQuotaRegistry()
        val info = registry.getCostQuota(AIProviderType.GEMINI)

        assertEquals(AIProviderType.GEMINI, info.providerType)
        assertFalse(info.costKnown)
        assertNull(info.estimatedCost)
        assertFalse(info.quotaKnown)
        assertNull(info.quotaRemaining)
    }

    @Test
    fun testExplicitCostQuotaMetadataIsStoredAndRetrieved() {
        val registry = CostQuotaRegistry()
        val explicitInfo = CostQuotaInfo(
            providerType = AIProviderType.GROQ,
            costKnown = true,
            estimatedCost = 0.00005,
            quotaKnown = true,
            quotaRemaining = 100000.0
        )

        registry.setCostQuota(explicitInfo)
        val retrieved = registry.getCostQuota(AIProviderType.GROQ)

        assertEquals(AIProviderType.GROQ, retrieved.providerType)
        assertTrue(retrieved.costKnown)
        assertEquals(0.00005, retrieved.estimatedCost!!, 0.000001)
        assertTrue(retrieved.quotaKnown)
        assertEquals(100000.0, retrieved.quotaRemaining!!, 0.01)

        // Reset clears it
        registry.reset(AIProviderType.GROQ)
        assertFalse(registry.getCostQuota(AIProviderType.GROQ).costKnown)
    }

    @Test
    fun testNoPersistenceIsIntroduced() {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val perfTracker = ProviderPerformanceTracker()
        perfTracker.recordRequestSuccess(AIProviderType.GEMINI, 150L)
        perfTracker.recordRequestFailure(AIProviderType.GROQ, 200L)

        val costRegistry = CostQuotaRegistry()
        costRegistry.setCostQuota(
            CostQuotaInfo(AIProviderType.OPENROUTER, costKnown = true, estimatedCost = 0.001)
        )

        // Ensure zero shared preferences keys were written
        assertTrue(prefs.all.isEmpty())
    }
}
