package com.example.ai.provider.selection

import android.content.Context
import com.example.ai.provider.AIProviderType
import com.example.ai.provider.cost.CostQuotaRegistry
import com.example.ai.provider.health.ProviderHealthTracker
import com.example.ai.provider.metadata.AIProviderMetadataRegistry
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.openrouter.OpenRouterProvider
import com.example.ai.provider.performance.ProviderPerformanceTracker
import com.example.ai.provider.routing.AutoRoutingEngine
import com.example.ai.provider.routing.RoutingDecision
import com.example.ai.provider.routing.RoutingTaskProfile

enum class SelectionMode {
    AUTO,
    MANUAL
}

/**
 * Manages model and provider selection with AUTO and MANUAL modes.
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
        val provider = if (isIncognito) {
            incognitoProvider ?: AIProviderType.GEMINI
        } else {
            val providerStr = sharedPrefs.getString(KEY_PROVIDER, AIProviderType.GEMINI.name)
            try {
                AIProviderType.valueOf(providerStr ?: AIProviderType.GEMINI.name)
            } catch (e: Exception) {
                AIProviderType.GEMINI
            }
        }
        return if (isProviderAvailable(provider)) provider else AIProviderType.GEMINI
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
