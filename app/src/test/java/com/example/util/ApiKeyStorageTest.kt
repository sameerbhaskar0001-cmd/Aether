package com.example.util

import com.example.ai.provider.AIProviderType
import com.example.ai.provider.GeminiProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiKeyStorageTest {

    @Test
    fun testProviderConfiguredStatus_noSecretLeakage() {
        val geminiKey = ApiKeyStorage.getGeminiKey()
        val geminiConfigured = ApiKeyStorage.isProviderConfigured(AIProviderType.GEMINI)

        // Output ONLY boolean/configuration state - never print actual secrets!
        println("=== PROVIDER CONFIGURATION VERIFICATION ===")
        println("Gemini configured = $geminiConfigured")
        println("===========================================")

        assertEquals(geminiKey.isNotBlank(), geminiConfigured)
    }

    @Test
    fun testSanitizeKey_removesExportAndQuotes() {
        val rawExport = "export GEMINI_API_KEY=\"AIza_test_secret_12345\""
        val sanitized = ApiKeyStorage.sanitizeKey(rawExport)
        assertEquals("AIza_test_secret_12345", sanitized)
    }

    @Test
    fun testProviderInstantiation() {
        val geminiProvider = GeminiProvider()
        assertEquals("Gemini", geminiProvider.providerName)
        assertEquals(AIProviderType.GEMINI, geminiProvider.type)
        assertEquals("gemini-2.5-flash", GeminiProvider.DEFAULT_MODEL_ID)
        assertEquals("gemini-2.5-pro", GeminiProvider.PRO_MODEL_ID)
        assertEquals("gemini-2.0-flash", GeminiProvider.FALLBACK_MODEL_ID)
    }
}
