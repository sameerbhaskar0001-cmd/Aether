package com.example.util

import com.example.ai.provider.AIProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApiKeyStorageTest {

    @Test
    fun testProviderConfiguredStatus_noSecretLeakage() {
        val geminiKey = ApiKeyStorage.getGeminiKey()
        val groqKey = ApiKeyStorage.getGroqKey()
        val openRouterKey = ApiKeyStorage.getOpenRouterKey()

        val geminiConfigured = ApiKeyStorage.isProviderConfigured(AIProviderType.GEMINI)
        val groqConfigured = ApiKeyStorage.isProviderConfigured(AIProviderType.GROQ)
        val openRouterConfigured = ApiKeyStorage.isProviderConfigured(AIProviderType.OPENROUTER)

        // Output ONLY boolean/configuration state - never print actual secrets!
        println("=== PROVIDER CONFIGURATION VERIFICATION ===")
        println("Gemini configured = $geminiConfigured")
        println("Groq configured = $groqConfigured")
        println("OpenRouter configured = $openRouterConfigured")
        println("===========================================")

        assertEquals(geminiKey.isNotBlank(), geminiConfigured)
        assertEquals(groqKey.isNotBlank(), groqConfigured)
        assertEquals(openRouterKey.isNotBlank(), openRouterConfigured)
    }

    @Test
    fun testSanitizeKey_removesExportAndQuotes() {
        val rawExport = "export OPENROUTER_API_KEY=\"sk-or-v1-123456789\""
        val sanitized = ApiKeyStorage.sanitizeKey(rawExport)
        assertEquals("sk-or-v1-123456789", sanitized)
    }

    @Test
    fun testProviderInstantiation() {
        if (ApiKeyStorage.isProviderConfigured(AIProviderType.GEMINI)) {
            val provider = com.example.ai.provider.GeminiProvider()
            println("GeminiProvider ready with configured credentials.")
        }
        if (ApiKeyStorage.isProviderConfigured(AIProviderType.GROQ)) {
            val provider = com.example.ai.provider.GroqProvider()
            println("GroqProvider ready with configured credentials.")
        }
        if (ApiKeyStorage.isProviderConfigured(AIProviderType.OPENROUTER)) {
            val provider = com.example.ai.provider.OpenRouterProvider()
            println("OpenRouterProvider ready with configured credentials.")
        }
    }
}
