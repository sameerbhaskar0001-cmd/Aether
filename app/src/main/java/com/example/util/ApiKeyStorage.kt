package com.example.util

import android.content.Context
import com.example.AetherApplication
import com.example.BuildConfig
import com.example.ai.provider.AIProviderType

/**
 * Centralized API Key storage and resolution manager.
 * Supports keys entered by the user in the UI (stored in SharedPreferences)
 * as well as keys injected via Environment variables or BuildConfig.
 */
object ApiKeyStorage {
    private const val PREFS_NAME = "user_api_keys_prefs"
    private const val KEY_GEMINI = "pref_gemini_api_key"
    private const val KEY_GROQ = "pref_groq_api_key"
    private const val KEY_OPENROUTER = "pref_openrouter_api_key"

    private fun getStoredKey(context: Context?, key: String): String {
        val ctx = context ?: try { AetherApplication.appContext } catch (e: Throwable) { null }
        if (ctx == null) return ""
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(key, "")?.trim() ?: ""
    }

    private fun saveKey(context: Context?, key: String, value: String) {
        val ctx = context ?: try { AetherApplication.appContext } catch (e: Throwable) { null } ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(key, value.trim()).apply()
    }

    fun sanitizeKey(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.removePrefix("export ").trim()
        }
        if (trimmed.contains("=")) {
            val parts = trimmed.split("=", limit = 2)
            trimmed = parts[1].trim()
        }
        return trimmed.removeSurrounding("\"").removeSurrounding("'").trim()
    }

    private fun isPlaceholder(value: String): Boolean {
        val cleaned = sanitizeKey(value)
        return cleaned.isBlank() ||
                cleaned == "MY_GEMINI_API_KEY" ||
                cleaned == "MY_GROQ_API_KEY" ||
                cleaned == "MY_OPENROUTER_API_KEY" ||
                cleaned == "MY_QWEN_KEY" ||
                cleaned.startsWith("MY_")
    }

    fun getGeminiKey(context: Context? = null): String {
        // 1. Build-time configured key (BuildConfig or System env)
        val buildConfigKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
        val cleanBuildConfig = sanitizeKey(buildConfigKey)
        if (cleanBuildConfig.isNotBlank() && !isPlaceholder(cleanBuildConfig)) {
            return cleanBuildConfig
        }

        val sysEnv = System.getenv("GEMINI_API_KEY") ?: ""
        val cleanSysEnv = sanitizeKey(sysEnv)
        if (cleanSysEnv.isNotBlank() && !isPlaceholder(cleanSysEnv)) {
            return cleanSysEnv
        }

        // 2. Optional in-app user override
        val userKey = getStoredKey(context, KEY_GEMINI)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
        }

        // 3. Unavailable
        return ""
    }

    fun getGroqKey(context: Context? = null): String {
        // 1. Build-time configured key (BuildConfig or System env)
        val buildConfigKey = try { BuildConfig.GROQ_API_KEY } catch (e: Throwable) { "" }
        val cleanBuildConfig = sanitizeKey(buildConfigKey)
        if (cleanBuildConfig.isNotBlank() && !isPlaceholder(cleanBuildConfig)) {
            return cleanBuildConfig
        }

        val sysEnv = System.getenv("GROQ_API_KEY") ?: ""
        val cleanSysEnv = sanitizeKey(sysEnv)
        if (cleanSysEnv.isNotBlank() && !isPlaceholder(cleanSysEnv)) {
            return cleanSysEnv
        }

        // 2. Optional in-app user override
        val userKey = getStoredKey(context, KEY_GROQ)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
        }

        // 3. Unavailable
        return ""
    }

    fun getOpenRouterKey(context: Context? = null): String {
        // 1. Build-time configured key (BuildConfig or System env)
        val buildConfigDirect = try {
            BuildConfig::class.java.getField("OPENROUTER_API_KEY").get(null) as? String ?: ""
        } catch (e: Throwable) { "" }
        val cleanDirect = sanitizeKey(buildConfigDirect)
        if (cleanDirect.isNotBlank() && !isPlaceholder(cleanDirect)) {
            return cleanDirect
        }

        val buildConfigQwen = try {
            BuildConfig::class.java.getField("Qwen").get(null) as? String ?: ""
        } catch (e: Throwable) { "" }
        val cleanQwen = sanitizeKey(buildConfigQwen)
        if (cleanQwen.isNotBlank() && !isPlaceholder(cleanQwen)) {
            return cleanQwen
        }

        val sysEnv = System.getenv("OPENROUTER_API_KEY") ?: ""
        val cleanSysEnv = sanitizeKey(sysEnv)
        if (cleanSysEnv.isNotBlank() && !isPlaceholder(cleanSysEnv)) {
            return cleanSysEnv
        }

        val sysQwen = System.getenv("Qwen") ?: ""
        val cleanSysQwen = sanitizeKey(sysQwen)
        if (cleanSysQwen.isNotBlank() && !isPlaceholder(cleanSysQwen)) {
            return cleanSysQwen
        }

        // 2. Optional in-app user override
        val userKey = getStoredKey(context, KEY_OPENROUTER)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
        }

        // 3. Unavailable
        return ""
    }

    fun isProviderConfigured(type: AIProviderType, context: Context? = null): Boolean {
        return when (type) {
            AIProviderType.GEMINI -> getGeminiKey(context).isNotBlank()
            AIProviderType.GROQ -> getGroqKey(context).isNotBlank()
            AIProviderType.OPENROUTER -> getOpenRouterKey(context).isNotBlank()
            AIProviderType.OPENAI, AIProviderType.CLAUDE -> false
        }
    }

    fun hasAnyProviderConfigured(context: Context? = null): Boolean {
        return isProviderConfigured(AIProviderType.GEMINI, context) ||
               isProviderConfigured(AIProviderType.GROQ, context) ||
               isProviderConfigured(AIProviderType.OPENROUTER, context)
    }

    fun saveGeminiKey(context: Context?, key: String) = saveKey(context, KEY_GEMINI, key)
    fun saveGroqKey(context: Context?, key: String) = saveKey(context, KEY_GROQ, key)
    fun saveOpenRouterKey(context: Context?, key: String) = saveKey(context, KEY_OPENROUTER, key)
}
