package com.example.ai.provider.selection

import android.content.Context
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.cost.CostQuotaRegistry
import com.example.ai.provider.health.ProviderHealthTracker
import com.example.ai.provider.metadata.AIProviderMetadataRegistry
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.performance.ProviderPerformanceTracker
import com.example.ai.provider.routing.AutoRoutingEngine
import com.example.ai.provider.routing.RoutingDecision
import com.example.ai.provider.routing.RoutingTaskProfile

enum class SelectionMode {
    AUTO,
    MANUAL
}

enum class GeminiModelVariant(
    val modelId: String,
    val displayName: String,
    val fallbackModelId: String = "gemini-3.5-flash"
) {
    FLASH_3_8("gemini-3.8-flash", "3.8 Flash", "gemini-3.5-flash"),
    PRO_3_1("gemini-3.1-pro-preview", "3.1 Pro", "gemini-3.8-flash");

    companion object {
        // Aliases for source and backward binary compatibility
        val FLASH_2_5: GeminiModelVariant get() = FLASH_3_8
        val PRO_2_5: GeminiModelVariant get() = PRO_3_1
        val PRO_3_8: GeminiModelVariant get() = PRO_3_1

        fun fromPreferenceOrModelId(raw: String?): GeminiModelVariant {
            return when (raw?.trim()) {
                "PRO_3_1", "PRO_2_5", "PRO_3_8", "gemini-3.1-pro-preview", "gemini-2.5-pro", "gemini-3.8-pro" -> PRO_3_1
                "FLASH_3_8", "FLASH_2_5", "gemini-3.8-flash", "gemini-2.5-flash" -> FLASH_3_8
                else -> FLASH_3_8
            }
        }
    }
}

/**
 * Manages model and provider selection with AUTO and MANUAL modes,
 * plus official Gemini 2.5 Flash / Pro variant switching.
 * Integrates AutoRoutingEngine for deterministic, signal-based provider routing.
 * Ensures strict privacy/Incognito isolation.
 */
open class ModelSelectionManager(
    private val context: Context,
    val healthTracker: ProviderHealthTracker = ProviderHealthTracker(),
    val performanceTracker: ProviderPerformanceTracker = ProviderPerformanceTracker().apply {
        // Known baseline performance signals: Groq fast LPU (150ms), Gemini (600ms), OpenRouter (900ms)
        recordRequestSuccess(AIProviderType.GROQ, 150L)
        recordRequestSuccess(AIProviderType.GEMINI, 600L)
        recordRequestSuccess(AIProviderType.OPENROUTER, 900L)
    },
    val costQuotaRegistry: CostQuotaRegistry = CostQuotaRegistry(),
    val requirementExtractor: com.example.ai.provider.routing.TaskRequirementExtractor = com.example.ai.provider.routing.DefaultTaskRequirementExtractor()
) {

    private val sharedPrefs by lazy {
        context.getSharedPreferences("model_selection_prefs", Context.MODE_PRIVATE)
    }

    // In-memory state for Incognito isolation
    private var incognitoMode: SelectionMode? = null
    private var incognitoProvider: AIProviderType? = null
    private var incognitoGeminiVariant: GeminiModelVariant? = null

    val metadataRegistry: AIProviderMetadataRegistry by lazy {
        AIProviderMetadataRegistry(isKeyConfigured = { isProviderAvailable(it) })
    }

    val routingEngine: AutoRoutingEngine by lazy {
        AutoRoutingEngine(
            metadataRegistry = metadataRegistry,
            healthTracker = healthTracker,
            performanceTracker = performanceTracker,
            costQuotaRegistry = costQuotaRegistry
        )
    }

    var lastRoutingDecision: RoutingDecision? = null
        private set

    companion object {
        private const val KEY_MODE = "selection_mode"
        private const val KEY_PROVIDER = "selected_provider"
        private const val KEY_GEMINI_VARIANT = "gemini_model_variant"
    }

    /**
     * Gets the active Gemini model variant (2.5 Flash vs 2.5 Pro), respecting Incognito isolation
     * and migrating any legacy 3.8 preference keys to official 2.5 model variants.
     */
    fun getGeminiModelVariant(isIncognito: Boolean): GeminiModelVariant {
        if (isIncognito) {
            return incognitoGeminiVariant ?: GeminiModelVariant.FLASH_2_5
        }
        val variantStr = sharedPrefs.getString(KEY_GEMINI_VARIANT, GeminiModelVariant.FLASH_2_5.name)
        return GeminiModelVariant.fromPreferenceOrModelId(variantStr)
    }

    /**
     * Saves the Gemini model variant. If isIncognito is true, keeps it in-memory only.
     */
    fun setGeminiModelVariant(variant: GeminiModelVariant, isIncognito: Boolean) {
        if (isIncognito) {
            incognitoGeminiVariant = variant
        } else {
            sharedPrefs.edit().putString(KEY_GEMINI_VARIANT, variant.name).apply()
        }
    }

    /**
     * Gets the active selection mode, respecting Incognito isolation.
     */
    open fun getSelectionMode(isIncognito: Boolean): SelectionMode {
        if (isIncognito) {
            return incognitoMode ?: SelectionMode.AUTO
        }
        val modeStr = sharedPrefs.getString(KEY_MODE, SelectionMode.AUTO.name)
        return try {
            SelectionMode.valueOf(modeStr ?: SelectionMode.AUTO.name)
        } catch (e: Exception) {
            SelectionMode.AUTO
        }
    }

    /**
     * Saves the selection mode. If isIncognito is true, keeps it in-memory only.
     */
    fun setSelectionMode(mode: SelectionMode, isIncognito: Boolean) {
        if (isIncognito) {
            incognitoMode = mode
        } else {
            sharedPrefs.edit().putString(KEY_MODE, mode.name).apply()
        }
    }

    /**
     * Gets the manually selected provider, respecting Incognito isolation.
     * If the persisted provider is unconfigured/unavailable, safely defaults to GEMINI.
     */
    fun getSelectedProvider(isIncognito: Boolean): AIProviderType {
        if (isIncognito) {
            return incognitoProvider ?: AIProviderType.GEMINI
        }
        val providerStr = sharedPrefs.getString(KEY_PROVIDER, AIProviderType.GEMINI.name)
        val provider = try {
            AIProviderType.valueOf(providerStr ?: AIProviderType.GEMINI.name)
        } catch (e: Exception) {
            AIProviderType.GEMINI
        }
        if (getSelectionMode(false) == SelectionMode.MANUAL) {
            return provider
        }
        return if (isProviderAvailable(provider)) provider else (getAvailableProviders().firstOrNull() ?: AIProviderType.GEMINI)
    }

    /**
     * Saves the manually selected provider. If isIncognito is true, keeps it in-memory only.
     */
    fun setSelectedProvider(provider: AIProviderType, isIncognito: Boolean) {
        if (isIncognito) {
            incognitoProvider = provider
        } else {
            sharedPrefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        }
    }

    /**
     * Checks if a provider has a configured API Key and is available.
     */
    open fun isProviderAvailable(type: AIProviderType): Boolean {
        return com.example.util.ApiKeyStorage.isProviderConfigured(type, context)
    }

    /**
     * Returns a list of all currently configured/available providers.
     */
    open fun getAvailableProviders(): List<AIProviderType> {
        return AIProviderType.values().filter { isProviderAvailable(it) }
    }

    /**
     * Builds a structured task requirements profile from safely available request and context.
     * Delegates to [TaskRequirementExtractor] for pure, deterministic extraction.
     */
    fun buildRoutingTaskProfile(request: ProviderRequest): RoutingTaskProfile {
        return requirementExtractor.extract(request)
    }

    /**
     * Dynamically and deterministically resolves the provider to use for a given request.
     * Manual selection strictly takes priority over Auto. Never silently switches in manual mode.
     * In Auto mode, uses AutoRoutingEngine to evaluate eligible providers.
     */
    open fun resolveProvider(request: ProviderRequest, isIncognito: Boolean): AIProviderType? {
        val mode = getSelectionMode(isIncognito)
        val manualProvider = getSelectedProvider(isIncognito)

        // 1. Manual Mode: return user selection strictly. Never silently switch.
        if (mode == SelectionMode.MANUAL) {
            return manualProvider
        }

        // 2. Auto Mode: evaluate structured task profile through AutoRoutingEngine
        val taskProfile = buildRoutingTaskProfile(request)
        val decision = routingEngine.evaluate(taskProfile)
        lastRoutingDecision = decision
        return decision.selectedProvider
    }
}
