package com.example.ai.provider.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.GeminiProvider
import com.example.ai.provider.groq.GroqProvider
import com.example.ai.provider.openrouter.OpenRouterProvider
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
class ProviderMetadataTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testConfiguredProviderIsRepresentedCorrectly() {
        // Registry configured such that Gemini is configured
        val registry = AIProviderMetadataRegistry(
            isKeyConfigured = { it == AIProviderType.GEMINI }
        )

        assertTrue(registry.isConfigured(AIProviderType.GEMINI))
        assertTrue(registry.isAvailable(AIProviderType.GEMINI))
        assertTrue(registry.isEligibleForAuto(AIProviderType.GEMINI))
        assertEquals(ProviderAvailabilityStatus.CONFIGURED, registry.getConfigurationStatus(AIProviderType.GEMINI))
        assertEquals(ProviderAvailabilityStatus.AVAILABLE, registry.getAvailabilityStatus(AIProviderType.GEMINI))

        val statusInfo = registry.getStatusInfo(AIProviderType.GEMINI)
        assertTrue(statusInfo.isConfigured)
        assertTrue(statusInfo.isAvailable)
        assertTrue(statusInfo.isEligibleForAuto)
        assertEquals(ProviderAvailabilityStatus.CONFIGURED, statusInfo.configurationStatus)
        assertEquals(ProviderAvailabilityStatus.AVAILABLE, statusInfo.availabilityStatus)
    }

    @Test
    fun testUnconfiguredProviderIsUnavailable() {
        // Registry configured such that Groq is unconfigured
        val registry = AIProviderMetadataRegistry(
            isKeyConfigured = { it != AIProviderType.GROQ }
        )

        assertFalse(registry.isConfigured(AIProviderType.GROQ))
        assertFalse(registry.isAvailable(AIProviderType.GROQ))
        assertFalse(registry.isEligibleForAuto(AIProviderType.GROQ))
        assertEquals(ProviderAvailabilityStatus.NOT_CONFIGURED, registry.getConfigurationStatus(AIProviderType.GROQ))
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, registry.getAvailabilityStatus(AIProviderType.GROQ))

        val statusInfo = registry.getStatusInfo(AIProviderType.GROQ)
        assertFalse(statusInfo.isConfigured)
        assertFalse(statusInfo.isAvailable)
        assertFalse(statusInfo.isEligibleForAuto)
        assertEquals(ProviderAvailabilityStatus.NOT_CONFIGURED, statusInfo.configurationStatus)
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, statusInfo.availabilityStatus)
    }

    @Test
    fun testInactiveOpenAiAndClaudeAreNotEligible() {
        // Even if an external key check would report true, inactive providers must remain unavailable
        val registry = AIProviderMetadataRegistry(
            isKeyConfigured = { true }
        )

        val activeTypes = registry.getActiveProviders().map { it.type }
        assertFalse(activeTypes.contains(AIProviderType.OPENAI))
        assertFalse(activeTypes.contains(AIProviderType.CLAUDE))

        assertFalse(registry.isConfigured(AIProviderType.OPENAI))
        assertFalse(registry.isAvailable(AIProviderType.OPENAI))
        assertFalse(registry.isEligibleForAuto(AIProviderType.OPENAI))
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, registry.getAvailabilityStatus(AIProviderType.OPENAI))

        assertFalse(registry.isConfigured(AIProviderType.CLAUDE))
        assertFalse(registry.isAvailable(AIProviderType.CLAUDE))
        assertFalse(registry.isEligibleForAuto(AIProviderType.CLAUDE))
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, registry.getAvailabilityStatus(AIProviderType.CLAUDE))

        val openAiStatus = registry.getStatusInfo(AIProviderType.OPENAI)
        assertFalse(openAiStatus.isAvailable)
        assertFalse(openAiStatus.isEligibleForAuto)
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, openAiStatus.availabilityStatus)

        val claudeStatus = registry.getStatusInfo(AIProviderType.CLAUDE)
        assertFalse(claudeStatus.isAvailable)
        assertFalse(claudeStatus.isEligibleForAuto)
        assertEquals(ProviderAvailabilityStatus.UNAVAILABLE, claudeStatus.availabilityStatus)
    }

    @Test
    fun testGeminiCapabilityMetadataIsExposedCorrectly() {
        val registry = AIProviderMetadataRegistry()
        val capabilities = registry.getCapabilities(AIProviderType.GEMINI)

        assertNotNull(capabilities)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities?.streaming)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities?.toolCalling)
        assertEquals(CapabilitySupport.UNKNOWN, capabilities?.vision)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities?.webSearch)
        assertEquals(1048576, capabilities?.maxContextTokens)

        // Verify provider implementation instance matches metadata
        val providerInstance = GeminiProvider()
        assertEquals(capabilities, providerInstance.capabilities)
    }

    @Test
    fun testGroqCapabilityMetadataIsExposedCorrectly() {
        val registry = AIProviderMetadataRegistry()
        val capabilities = registry.getCapabilities(AIProviderType.GROQ)

        assertNotNull(capabilities)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities?.streaming)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.toolCalling)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.vision)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.webSearch)
        assertEquals(8192, capabilities?.maxContextTokens)

        // Verify provider implementation instance matches metadata
        val providerInstance = GroqProvider()
        assertEquals(capabilities, providerInstance.capabilities)
    }

    @Test
    fun testOpenRouterCapabilityMetadataIsExposedCorrectly() {
        val registry = AIProviderMetadataRegistry()
        val capabilities = registry.getCapabilities(AIProviderType.OPENROUTER)

        assertNotNull(capabilities)
        assertEquals(CapabilitySupport.SUPPORTED, capabilities?.streaming)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.toolCalling)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.vision)
        assertEquals(CapabilitySupport.UNSUPPORTED, capabilities?.webSearch)
        assertEquals(32768, capabilities?.maxContextTokens)

        // Verify provider implementation instance matches metadata
        val providerInstance = OpenRouterProvider()
        assertEquals(capabilities, providerInstance.capabilities)
    }

    @Test
    fun testProviderAndModelSeparationWorks() {
        val registry = AIProviderMetadataRegistry()

        // Verify provider-level metadata vs model-level metadata
        val openRouterModels = registry.getModels(AIProviderType.OPENROUTER)
        assertEquals(1, openRouterModels.size)

        val defaultModel = registry.getDefaultModel(AIProviderType.OPENROUTER)
        assertNotNull(defaultModel)
        assertEquals(OpenRouterProvider.DEFAULT_MODEL, defaultModel?.modelId)
        assertEquals("Qwen 2 7B Instruct (Free)", defaultModel?.displayName)
        assertEquals(AIProviderType.OPENROUTER, defaultModel?.providerType)
        assertTrue(defaultModel?.isDefault == true)
        assertEquals(32768, defaultModel?.maxContextTokens)

        // Verify distinct provider vs model entities
        val providerMeta = registry.getProviderMetadata(AIProviderType.OPENROUTER)
        assertNotNull(providerMeta)
        assertEquals("OpenRouter", providerMeta?.displayName)
        assertEquals(defaultModel?.modelId, providerMeta?.defaultModelId)

        val activeModel = registry.getActiveModel(AIProviderType.OPENROUTER)
        assertNotNull(activeModel)
        assertEquals(OpenRouterProvider.DEFAULT_MODEL, activeModel?.modelId)

        // Verify a provider can expose multiple models separately
        val secondModel = ModelMetadata(
            modelId = "qwen/qwen-2.5-72b-instruct",
            displayName = "Qwen 2.5 72B Instruct",
            providerType = AIProviderType.OPENROUTER,
            capabilities = providerMeta!!.capabilities,
            maxContextTokens = 65536,
            isDefault = false
        )
        registry.registerProvider(
            providerMeta.copy(models = providerMeta.models + secondModel)
        )
        val updatedModels = registry.getModels(AIProviderType.OPENROUTER)
        assertEquals(2, updatedModels.size)
        assertTrue(updatedModels.any { it.modelId == "qwen/qwen-2.5-72b-instruct" })
    }

    @Test
    fun testUnknownCapabilityDoesNotBecomeFalselyReportedAsSupported() {
        val customCapabilities = ProviderCapabilities(
            streaming = CapabilitySupport.SUPPORTED,
            toolCalling = CapabilitySupport.UNKNOWN,
            vision = CapabilitySupport.UNKNOWN,
            webSearch = CapabilitySupport.UNSUPPORTED,
            maxContextTokens = null
        )

        // Explicit boolean check
        assertFalse(customCapabilities.streaming == CapabilitySupport.UNKNOWN)
        assertTrue(customCapabilities.streaming.isSupported)

        // UNKNOWN must not be reported as supported
        assertFalse(customCapabilities.toolCalling.isSupported)
        assertFalse(customCapabilities.vision.isSupported)
        assertFalse(customCapabilities.isSupported { it.toolCalling })
        assertFalse(customCapabilities.isSupported { it.vision })

        // UNSUPPORTED must not be reported as supported
        assertFalse(customCapabilities.webSearch.isSupported)
        assertFalse(customCapabilities.isSupported { it.webSearch })

        // Verify enum distinction
        assertEquals(CapabilitySupport.UNKNOWN, customCapabilities.vision)
        assertEquals(CapabilitySupport.UNSUPPORTED, customCapabilities.webSearch)
        assertEquals(CapabilitySupport.SUPPORTED, customCapabilities.streaming)
    }

    @Test
    fun testIncognitoDoesNotIntroducePersistentProviderMetadata() {
        val prefs = context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val selectionManager = ModelSelectionManager(context)

        // Perform operations in Incognito mode
        selectionManager.setSelectionMode(SelectionMode.MANUAL, isIncognito = true)
        selectionManager.setSelectedProvider(AIProviderType.GROQ, isIncognito = true)

        // Query metadata in Incognito
        val registry = AIProviderMetadataRegistry()
        val incognitoProvider = selectionManager.getSelectedProvider(isIncognito = true)
        assertEquals(AIProviderType.GROQ, incognitoProvider)

        val capabilities = registry.getCapabilities(incognitoProvider)
        assertNotNull(capabilities)

        val statusInfo = registry.getStatusInfo(incognitoProvider)
        assertNotNull(statusInfo)

        // Verify SharedPreferences has zero stored keys or persistent provider metadata
        assertTrue(prefs.all.isEmpty())
    }
}
