package com.example.ai.provider.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AIProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderHealthTrackerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testInitialStateIsUnknown() {
        val tracker = ProviderHealthTracker()
        val health = tracker.getHealth(AIProviderType.GEMINI)

        assertEquals(AIProviderType.GEMINI, health.providerType)
        assertEquals(ProviderHealthState.UNKNOWN, health.healthState)
        assertEquals(0, health.consecutiveFailures)
        assertNull(health.lastFailureTimestamp)
        assertNull(health.lastSuccessTimestamp)
    }

    @Test
    fun testSuccessProducesHealthyAndZeroFailures() {
        val tracker = ProviderHealthTracker()
        val timestamp = 1700000000000L

        tracker.recordSuccess(AIProviderType.GEMINI, timestamp)
        val health = tracker.getHealth(AIProviderType.GEMINI)

        assertEquals(ProviderHealthState.HEALTHY, health.healthState)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(timestamp, health.lastSuccessTimestamp)
        assertNull(health.lastFailureTimestamp)
    }

    @Test
    fun testOneFailureDoesNotBecomeUnavailable() {
        val tracker = ProviderHealthTracker()
        tracker.recordSuccess(AIProviderType.GEMINI)
        assertEquals(ProviderHealthState.HEALTHY, tracker.getHealth(AIProviderType.GEMINI).healthState)

        val failTime = 1700000005000L
        tracker.recordFailure(AIProviderType.GEMINI, failTime)
        val health = tracker.getHealth(AIProviderType.GEMINI)

        assertEquals(1, health.consecutiveFailures)
        assertNotEquals(ProviderHealthState.UNAVAILABLE, health.healthState)
        assertEquals(ProviderHealthState.HEALTHY, health.healthState)
        assertEquals(failTime, health.lastFailureTimestamp)
    }

    @Test
    fun testThresholdConsecutiveFailuresBecomesDegraded() {
        val tracker = ProviderHealthTracker(failureThreshold = 3)

        tracker.recordFailure(AIProviderType.GROQ)
        assertEquals(1, tracker.getHealth(AIProviderType.GROQ).consecutiveFailures)
        assertNotEquals(ProviderHealthState.DEGRADED, tracker.getHealth(AIProviderType.GROQ).healthState)

        tracker.recordFailure(AIProviderType.GROQ)
        assertEquals(2, tracker.getHealth(AIProviderType.GROQ).consecutiveFailures)
        assertNotEquals(ProviderHealthState.DEGRADED, tracker.getHealth(AIProviderType.GROQ).healthState)

        tracker.recordFailure(AIProviderType.GROQ)
        assertEquals(3, tracker.getHealth(AIProviderType.GROQ).consecutiveFailures)
        assertEquals(ProviderHealthState.DEGRADED, tracker.getHealth(AIProviderType.GROQ).healthState)
        assertNotEquals(ProviderHealthState.UNAVAILABLE, tracker.getHealth(AIProviderType.GROQ).healthState)
    }

    @Test
    fun testSuccessAfterFailuresResetsFailureCount() {
        val tracker = ProviderHealthTracker(failureThreshold = 3)

        tracker.recordFailure(AIProviderType.OPENROUTER)
        tracker.recordFailure(AIProviderType.OPENROUTER)
        tracker.recordFailure(AIProviderType.OPENROUTER)
        assertEquals(3, tracker.getHealth(AIProviderType.OPENROUTER).consecutiveFailures)
        assertEquals(ProviderHealthState.DEGRADED, tracker.getHealth(AIProviderType.OPENROUTER).healthState)

        val recoverTime = 1700000010000L
        tracker.recordSuccess(AIProviderType.OPENROUTER, recoverTime)
        val health = tracker.getHealth(AIProviderType.OPENROUTER)

        assertEquals(ProviderHealthState.HEALTHY, health.healthState)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(recoverTime, health.lastSuccessTimestamp)
    }

    @Test
    fun testResetReturnsUnknown() {
        val tracker = ProviderHealthTracker()
        tracker.recordSuccess(AIProviderType.GEMINI)
        assertEquals(ProviderHealthState.HEALTHY, tracker.getHealth(AIProviderType.GEMINI).healthState)

        tracker.reset(AIProviderType.GEMINI)
        val health = tracker.getHealth(AIProviderType.GEMINI)

        assertEquals(ProviderHealthState.UNKNOWN, health.healthState)
        assertEquals(0, health.consecutiveFailures)
        assertNull(health.lastSuccessTimestamp)
        assertNull(health.lastFailureTimestamp)
    }

    @Test
    fun testProviderStatesRemainIndependent() {
        val tracker = ProviderHealthTracker(failureThreshold = 3)

        tracker.recordSuccess(AIProviderType.GEMINI)

        tracker.recordFailure(AIProviderType.GROQ)
        tracker.recordFailure(AIProviderType.GROQ)
        tracker.recordFailure(AIProviderType.GROQ)

        val geminiHealth = tracker.getHealth(AIProviderType.GEMINI)
        val groqHealth = tracker.getHealth(AIProviderType.GROQ)
        val openRouterHealth = tracker.getHealth(AIProviderType.OPENROUTER)

        assertEquals(ProviderHealthState.HEALTHY, geminiHealth.healthState)
        assertEquals(0, geminiHealth.consecutiveFailures)

        assertEquals(ProviderHealthState.DEGRADED, groqHealth.healthState)
        assertEquals(3, groqHealth.consecutiveFailures)

        assertEquals(ProviderHealthState.UNKNOWN, openRouterHealth.healthState)
        assertEquals(0, openRouterHealth.consecutiveFailures)
    }

    @Test
    fun testNoPersistenceOrIncognitoStateIntroduced() {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val tracker = ProviderHealthTracker()
        tracker.recordSuccess(AIProviderType.GEMINI)
        tracker.recordFailure(AIProviderType.GROQ)
        tracker.recordFailure(AIProviderType.GROQ)
        tracker.recordFailure(AIProviderType.GROQ)

        // Verify zero persistent keys were stored
        assertTrue(prefs.all.isEmpty())
    }
}
