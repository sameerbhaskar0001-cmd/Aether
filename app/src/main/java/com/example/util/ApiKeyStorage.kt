package com.example.util

import android.content.Context
import com.example.AetherApplication
import com.example.BuildConfig
import com.example.ai.provider.AIProviderType

/**
 * Classification of how API credentials are provisioned at runtime.
 *
 * SECURITY NOTE:
 * Any key compiled into `BuildConfig` is embedded in the APK's DEX bytecode in plaintext
 * and can be trivially extracted via `jadx` or `apktool`. Therefore, `BUILD_CONFIG_PROTOTYPE`
 * is strictly treated as a non-secret local/dev prototype fallback. Production deployments
 * should configure a backend proxy (`BACKEND_PROXY`) or user-supplied runtime credentials.
 */
enum class ApiKeySecurityMode {
    BACKEND_PROXY,
    RUNTIME_OVERRIDE,
    BUILD_CONFIG_PROTOTYPE,
    UNCONFIGURED
}

data class ApiKeySecurityAudit(
    val mode: ApiKeySecurityMode,
    val isEmbeddedInApk: Boolean,
    val usesBackendProxy: Boolean,
    val isProductionSafe: Boolean,
    val securityNotice: String
)

/**
 * Centralized API Key and Backend Proxy resolution manager.
 *
 * Resolution precedence:
 * 1. Backend Proxy (`PROXY_MANAGED_SESSION`) when a proxy base URL is configured.
 * 2. Runtime user-provided key stored in device-local `SharedPreferences` or system env.
 * 3. `BuildConfig` key (explicitly flagged as non-secret prototype fallback).
 */
object ApiKeyStorage {
    private const val PREFS_NAME = "user_api_keys_prefs"
    private const val KEY_GEMINI = "pref_gemini_api_key"
    private const val KEY_GROQ = "pref_groq_api_key"
    private const val KEY_OPENROUTER = "pref_openrouter_api_key"
    private const val KEY_GEMINI_PROXY_URL = "pref_gemini_proxy_base_url"
    private const val KEY_GEMINI_PROXY_TOKEN = "pref_gemini_proxy_auth_token"

    const val PROXY_SENTINEL_KEY = "PROXY_MANAGED_SESSION"

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

    fun getGeminiProxyBaseUrl(context: Context? = null): String {
        val stored = getStoredKey(context, KEY_GEMINI_PROXY_URL).trim()
        if (stored.isNotBlank() && (stored.startsWith("https://") || stored.startsWith("http://"))) {
            return if (stored.endsWith("/")) stored else "$stored/"
        }
        val envProxy = (System.getenv("AETHER_GEMINI_PROXY_URL") ?: "").trim()
        if (envProxy.isNotBlank() && (envProxy.startsWith("https://") || envProxy.startsWith("http://"))) {
            return if (envProxy.endsWith("/")) envProxy else "$envProxy/"
        }
        return ""
    }

    fun saveGeminiProxyBaseUrl(context: Context?, proxyBaseUrl: String) {
        saveKey(context, KEY_GEMINI_PROXY_URL, proxyBaseUrl)
    }

    fun getProxyAuthToken(context: Context? = null): String {
        val stored = getStoredKey(context, KEY_GEMINI_PROXY_TOKEN)
        if (stored.isNotBlank()) return stored
        return (System.getenv("AETHER_GEMINI_PROXY_TOKEN") ?: "").trim()
    }

    fun saveProxyAuthToken(context: Context?, token: String) {
        saveKey(context, KEY_GEMINI_PROXY_TOKEN, token)
    }

    fun isBackendProxyConfigured(context: Context? = null): Boolean {
        return getGeminiProxyBaseUrl(context).isNotBlank()
    }

    fun hasEmbeddedBuildConfigKey(): Boolean {
        val buildConfigKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
        val clean = sanitizeKey(buildConfigKey)
        return clean.isNotBlank() && !isPlaceholder(clean)
    }

    /**
     * Audits the active API credential posture without ever exposing or logging secret values.
     */
    fun auditSecurity(context: Context? = null): ApiKeySecurityAudit {
        val proxyConfigured = isBackendProxyConfigured(context)
        val embeddedKeyPresent = hasEmbeddedBuildConfigKey()
        val userKeyPresent = sanitizeKey(getStoredKey(context, KEY_GEMINI)).let {
            it.isNotBlank() && !isPlaceholder(it)
        }

        return when {
            proxyConfigured -> ApiKeySecurityAudit(
                mode = ApiKeySecurityMode.BACKEND_PROXY,
                isEmbeddedInApk = embeddedKeyPresent,
                usesBackendProxy = true,
                isProductionSafe = !embeddedKeyPresent,
                securityNotice = if (embeddedKeyPresent) {
                    "Requests route through backend proxy, but an embedded BuildConfig key is still present in APK bytecode."
                } else {
                    "Requests route through backend proxy without client-embedded API keys."
                }
            )
            userKeyPresent -> ApiKeySecurityAudit(
                mode = ApiKeySecurityMode.RUNTIME_OVERRIDE,
                isEmbeddedInApk = embeddedKeyPresent,
                usesBackendProxy = false,
                isProductionSafe = false,
                securityNotice = "Using user-supplied runtime API key from local device storage. Use a backend proxy for production builds."
            )
            embeddedKeyPresent -> ApiKeySecurityAudit(
                mode = ApiKeySecurityMode.BUILD_CONFIG_PROTOTYPE,
                isEmbeddedInApk = true,
                usesBackendProxy = false,
                isProductionSafe = false,
                securityNotice = "BuildConfig API keys are embedded in APK bytecode and are NOT secret. Suitable only for local/prototype builds; configure a backend proxy for production."
            )
            else -> ApiKeySecurityAudit(
                mode = ApiKeySecurityMode.UNCONFIGURED,
                isEmbeddedInApk = false,
                usesBackendProxy = false,
                isProductionSafe = true,
                securityNotice = "No client API key or backend proxy configured."
            )
        }
    }

    fun getGeminiKey(context: Context? = null): String {
        // 1. Optional runtime user override takes priority over static BuildConfig prototype keys
        val userKey = getStoredKey(context, KEY_GEMINI)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
        }

        // 2. Backend proxy mode (server attaches upstream Gemini key; client never sends raw key in URL)
        if (isBackendProxyConfigured(context)) {
            return PROXY_SENTINEL_KEY
        }

        // 3. System environment variable
        val sysEnv = System.getenv("GEMINI_API_KEY") ?: ""
        val cleanSysEnv = sanitizeKey(sysEnv)
        if (cleanSysEnv.isNotBlank() && !isPlaceholder(cleanSysEnv)) {
            return cleanSysEnv
        }

        // 4. BuildConfig prototype fallback (non-secret; embedded in APK DEX)
        val buildConfigKey = try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
        val cleanBuildConfig = sanitizeKey(buildConfigKey)
        if (cleanBuildConfig.isNotBlank() && !isPlaceholder(cleanBuildConfig)) {
            return cleanBuildConfig
        }

        return ""
    }

    fun getGroqKey(context: Context? = null): String {
        val userKey = getStoredKey(context, KEY_GROQ)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
        }

        val sysEnv = System.getenv("GROQ_API_KEY") ?: ""
        val cleanSysEnv = sanitizeKey(sysEnv)
        if (cleanSysEnv.isNotBlank() && !isPlaceholder(cleanSysEnv)) {
            return cleanSysEnv
        }

        val buildConfigKey = try { BuildConfig.GROQ_API_KEY } catch (e: Throwable) { "" }
        val cleanBuildConfig = sanitizeKey(buildConfigKey)
        if (cleanBuildConfig.isNotBlank() && !isPlaceholder(cleanBuildConfig)) {
            return cleanBuildConfig
        }

        return ""
    }

    fun getOpenRouterKey(context: Context? = null): String {
        val userKey = getStoredKey(context, KEY_OPENROUTER)
        val cleanUserKey = sanitizeKey(userKey)
        if (cleanUserKey.isNotBlank() && !isPlaceholder(cleanUserKey)) {
            return cleanUserKey
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

        return ""
    }

    fun isProviderConfigured(type: AIProviderType, context: Context? = null): Boolean {
        return when (type) {
            AIProviderType.GEMINI -> isBackendProxyConfigured(context) || getGeminiKey(context).isNotBlank()
            AIProviderType.GROQ -> getGroqKey(context).isNotBlank()
            AIProviderType.OPENROUTER -> getOpenRouterKey(context).isNotBlank()
            AIProviderType.OPENAI,
            AIProviderType.CLAUDE -> false
        }
    }

    fun hasAnyProviderConfigured(context: Context? = null): Boolean {
        return isProviderConfigured(AIProviderType.GEMINI, context)
    }

    fun saveGeminiKey(context: Context?, key: String) = saveKey(context, KEY_GEMINI, key)
    fun saveGroqKey(context: Context?, key: String) = saveKey(context, KEY_GROQ, key)
    fun saveOpenRouterKey(context: Context?, key: String) = saveKey(context, KEY_OPENROUTER, key)
}
