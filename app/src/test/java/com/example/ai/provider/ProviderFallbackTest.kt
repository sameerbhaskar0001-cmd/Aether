package com.example.ai.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiAssistantService
import com.example.ai.SystemTimeProvider
import com.example.ai.provider.models.ProviderException
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderResponse
import com.example.ai.provider.models.ProviderStreamChunk
import com.example.ai.provider.selection.ModelSelectionManager
import com.example.ai.provider.selection.SelectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderFallbackTest {

    private lateinit var context: Context
    private lateinit var fakeSelectionManager: ModelSelectionManager

    class FakeProvider(
        override val type: AIProviderType,
        override val providerName: String,
        var shouldFailRecoverably: Boolean = false,
        var shouldFailNonRecoverably: Boolean = false,
        var chunksToEmit: List<String> = emptyList(),
        var failAfterEmitting: Boolean = false
    ) : AIProvider {

        override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> = flow {
            if (shouldFailNonRecoverably) {
                throw ProviderException.AuthenticationError("Invalid API Key")
            }
            if (shouldFailRecoverably && !failAfterEmitting) {
                throw ProviderException.NetworkError("Timeout connecting")
            }

            chunksToEmit.forEach { text ->
                emit(ProviderStreamChunk(textDelta = text, isComplete = false, providerName = providerName, accumulatedText = text))
            }

            if (shouldFailRecoverably && failAfterEmitting) {
                throw ProviderException.NetworkError("Network dropped mid-stream")
            }

            emit(ProviderStreamChunk(textDelta = "", isComplete = true, providerName = providerName, accumulatedText = ""))
        }

        override suspend fun generate(request: ProviderRequest): ProviderResponse {
            if (shouldFailNonRecoverably) throw ProviderException.AuthenticationError("Invalid API Key")
            if (shouldFailRecoverably) throw ProviderException.NetworkError("Timeout")
            return ProviderResponse("Standard Text", providerName)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAutoPrimarySucceedsNoFallback() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", chunksToEmit = listOf("Hello from Gemini"))
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", shouldFailRecoverably = true)

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        val results = service.generateResponseStream("hello", emptyList()).toList()
        assertEquals(2, results.size)
        assertEquals("Hello from Gemini", results[0])
        assertEquals("GEMINI", service.lastUsedProviderFlow.filterNotNull().first())
    }

    @Test
    fun testAutoPrimaryRecoverableFailureFallbackSucceeds() = runBlocking {
        // Gemini fails recoverably, falling back to Groq which succeeds
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Hello from Groq"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        val results = service.generateResponseStream("hello", emptyList()).toList()
        assertEquals(2, results.size)
        assertEquals("Hello from Groq", results[0])
        assertEquals("GROQ (FALLBACK)", service.lastUsedProviderFlow.filterNotNull().first())
    }

    @Test
    fun testAutoPrimaryNonRecoverableFailureNoFallback() = runBlocking {
        // Gemini fails with AuthenticationError, which is non-recoverable, so no fallback occurs
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailNonRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Hello from Groq"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected non-recoverable AuthenticationError")
        } catch (e: ProviderException.AuthenticationError) {
            assertEquals("Invalid API Key", e.message)
        }
    }

    @Test
    fun testManualSelectedProviderFailsNoSilentFallback() = runBlocking {
        // In MANUAL mode, if selected provider fails, throw exception immediately without falling back
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Hello from Groq"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.MANUAL
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected manual provider error to be rethrown without fallback")
        } catch (e: ProviderException.NetworkError) {
            assertEquals("Timeout connecting", e.message)
        }
    }

    @Test
    fun testStreamingFailureAfterMeaningfulContentNoFallback() = runBlocking {
        // Gemini emits content first, then fails. It should NOT fall back to Groq!
        val geminiFake = FakeProvider(
            AIProviderType.GEMINI, 
            "Gemini", 
            chunksToEmit = listOf("Partial answer"), 
            shouldFailRecoverably = true,
            failAfterEmitting = true
        )
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Full fallback answer"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected mid-stream network dropped exception to be rethrown without fallback")
        } catch (e: ProviderException.NetworkError) {
            assertEquals("Network dropped mid-stream", e.message)
        }
    }

    @Test
    fun testAutoModeChainFallbackSucceeds() = runBlocking {
        // Primary (Gemini) fails recoverably
        // First fallback (Groq) fails recoverably
        // Second fallback (OpenRouter) succeeds
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", shouldFailRecoverably = true)
        val openRouterFake = FakeProvider(AIProviderType.OPENROUTER, "OpenRouter", chunksToEmit = listOf("Success from OpenRouter"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ, AIProviderType.OPENROUTER)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake,
                AIProviderType.OPENROUTER to openRouterFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        val results = service.generateResponseStream("hello", emptyList()).toList()
        assertEquals(2, results.size)
        assertEquals("Success from OpenRouter", results[0])
        assertEquals("OPENROUTER (FALLBACK)", service.lastUsedProviderFlow.filterNotNull().first())
    }

    @Test
    fun testAutoModeAllProvidersFailRecoverably() = runBlocking {
        // All eligible providers fail recoverably
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", shouldFailRecoverably = true)

        var geminiAttempts = 0
        var groqAttempts = 0

        val geminiSpy = object : AIProvider by geminiFake {
            override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> {
                geminiAttempts++
                return geminiFake.generateStream(request)
            }
        }
        val groqSpy = object : AIProvider by groqFake {
            override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> {
                groqAttempts++
                return groqFake.generateStream(request)
            }
        }

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiSpy,
                AIProviderType.GROQ to groqSpy
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected complete failure")
        } catch (e: ProviderException.NetworkError) {
            assertEquals("Timeout connecting", e.message)
        }

        assertEquals(1, geminiAttempts)
        assertEquals(1, groqAttempts)
    }

    @Test
    fun testInactiveProvidersAreNeverSelectedAsFallback() = runBlocking {
        // Gemini fails recoverably. Groq is inactive.
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Groq success"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            // Only GEMINI is returned as available/active. GROQ is unconfigured/inactive.
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected failure as Groq is unconfigured/inactive and shouldn't be selected")
        } catch (e: ProviderException.NetworkError) {
            assertEquals("Timeout connecting", e.message)
        }
    }

    @Test
    fun testRetryLoopProtectionEnsuresBoundedAttempts() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        var attempts = 0
        val geminiSpy = object : AIProvider by geminiFake {
            override fun generateStream(request: ProviderRequest): Flow<ProviderStreamChunk> {
                attempts++
                return geminiFake.generateStream(request)
            }
        }

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiSpy
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        try {
            service.generateResponseStream("hello", emptyList()).toList()
            fail("Expected failure")
        } catch (e: ProviderException.NetworkError) {
            assertEquals("Timeout connecting", e.message)
        }

        assertEquals(1, attempts) // Strictly exactly 1 attempt on single provider, no loop
    }

    @Test
    fun testStreamingFailureBeforeMeaningfulContentSucceeds() = runBlocking {
        // Gemini starts streaming but fails BEFORE emitting any meaningful text delta (only emits blanks)
        val geminiFake = FakeProvider(
            AIProviderType.GEMINI, 
            "Gemini", 
            chunksToEmit = listOf(" ", "\n", ""), 
            shouldFailRecoverably = true,
            failAfterEmitting = true
        )
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Fallback answer"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        val results = service.generateResponseStream("hello", emptyList()).toList()
        // Blanks from Gemini are emitted, and then Fallback answer from Groq is appended
        assertTrue(results.contains("Fallback answer"))
        assertEquals("GROQ (FALLBACK)", service.lastUsedProviderFlow.filterNotNull().first())
    }

    @Test
    fun testLastUsedProviderFlowReportsActualSuccessfulProvider() = runBlocking {
        val geminiFake = FakeProvider(AIProviderType.GEMINI, "Gemini", shouldFailRecoverably = true)
        val groqFake = FakeProvider(AIProviderType.GROQ, "Groq", chunksToEmit = listOf("Groq reply"))

        fakeSelectionManager = object : ModelSelectionManager(context) {
            override fun getSelectionMode(isIncognito: Boolean) = SelectionMode.AUTO
            override fun getAvailableProviders() = listOf(AIProviderType.GEMINI, AIProviderType.GROQ)
            override fun resolveProvider(request: ProviderRequest, isIncognito: Boolean) = AIProviderType.GEMINI
        }

        val factory = AIProviderFactory(
            providerMap = mapOf(
                AIProviderType.GEMINI to geminiFake,
                AIProviderType.GROQ to groqFake
            )
        )

        val service = GeminiAssistantService(
            timeProvider = SystemTimeProvider(),
            selectionManager = fakeSelectionManager,
            providerFactory = factory
        )

        val results = service.generateResponseStream("hello", emptyList()).toList()
        assertEquals("GROQ (FALLBACK)", service.lastUsedProviderFlow.filterNotNull().first())
    }

    @Test
    fun testIncognitoFallbackStateIsInMemoryOnly() {
        val manager = ModelSelectionManager(context)
        
        // Save state in incognito
        manager.setSelectionMode(SelectionMode.MANUAL, isIncognito = true)
        manager.setSelectedProvider(AIProviderType.GROQ, isIncognito = true)

        // Verify state is manual & GROQ for incognito mode
        assertEquals(SelectionMode.MANUAL, manager.getSelectionMode(isIncognito = true))
        assertEquals(AIProviderType.GROQ, manager.getSelectedProvider(isIncognito = true))

        // Verify normal state remains unaffected (defaults to AUTO & GEMINI)
        assertEquals(SelectionMode.AUTO, manager.getSelectionMode(isIncognito = false))
        assertEquals(AIProviderType.GEMINI, manager.getSelectedProvider(isIncognito = false))
    }
}
