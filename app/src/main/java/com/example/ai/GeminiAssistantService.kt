package com.example.ai

import com.example.ai.provider.AIProvider
import com.example.ai.provider.GeminiProvider
import com.example.ai.provider.models.ProviderImageAttachment
import com.example.ai.provider.models.ProviderMessage
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.provider.models.ProviderRole
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service adapter bridging ChatViewModel / AssistantService contract to the AIProvider interface.
 */
class GeminiAssistantService(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    val toolRegistry: com.example.ai.tool.ToolRegistry? = null,
    private val toolOrchestrator: com.example.ai.tool.orchestrator.ToolOrchestrator? = null,
    private val selectionManager: com.example.ai.provider.selection.ModelSelectionManager? = null,
    private val provider: AIProvider? = null,
    private val providerFactory: com.example.ai.provider.AIProviderFactory = com.example.ai.provider.AIProviderFactory(
        timeProvider = timeProvider,
        providerMap = mapOf(
            com.example.ai.provider.AIProviderType.GROQ to com.example.ai.provider.groq.GroqProvider(
                timeProvider = timeProvider
            ),
            com.example.ai.provider.AIProviderType.OPENROUTER to com.example.ai.provider.openrouter.OpenRouterProvider(
                timeProvider = timeProvider
            ),
            com.example.ai.provider.AIProviderType.GEMINI to (provider ?: GeminiProvider(
                timeProvider = timeProvider,
                toolRegistry = toolRegistry,
                toolOrchestrator = toolOrchestrator
            ))
        )
    ),
    healthTracker: com.example.ai.provider.health.ProviderHealthTracker? = null,
    performanceTracker: com.example.ai.provider.performance.ProviderPerformanceTracker? = null
) : AssistantService {

    private val effectiveHealthTracker = healthTracker ?: selectionManager?.healthTracker ?: com.example.ai.provider.health.ProviderHealthTracker()
    private val effectivePerfTracker = performanceTracker ?: selectionManager?.performanceTracker ?: com.example.ai.provider.performance.ProviderPerformanceTracker()

    private val _lastUsedProvider = MutableStateFlow<String?>(null)
    override val lastUsedProviderFlow: Flow<String?> = _lastUsedProvider.asStateFlow()

    private val _lastUsedModel = MutableStateFlow<String?>(null)
    override val lastUsedModelFlow: Flow<String?> = _lastUsedModel.asStateFlow()

    private fun isRecoverableException(t: Throwable): Boolean {
        return when (t) {
            is com.example.ai.provider.models.ProviderException.NetworkError -> true
            is com.example.ai.provider.models.ProviderException.RateLimitError -> true
            is com.example.ai.provider.models.ProviderException.ProviderUnavailableError -> true
            is java.io.IOException -> true
            is java.util.concurrent.TimeoutException -> true
            else -> false
        }
    }

    override fun generateResponseStream(
        userMessage: String, 
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory>,
        fileContext: String?,
        isIncognito: Boolean
    ): Flow<String> = generateResponseStream(
        userMessage = userMessage,
        history = history,
        relevantMemories = relevantMemories,
        fileContext = fileContext,
        isIncognito = isIncognito,
        requiresToolCalling = false,
        hasVisionInput = false,
        requiresWebSearch = false,
        imageAttachment = null,
        preferredModelId = null
    )

    override fun generateResponseStream(
        userMessage: String,
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory>,
        fileContext: String?,
        isIncognito: Boolean,
        imageAttachment: ProviderImageAttachment?,
        preferredModelId: String?
    ): Flow<String> = generateResponseStream(
        userMessage = userMessage,
        history = history,
        relevantMemories = relevantMemories,
        fileContext = fileContext,
        isIncognito = isIncognito,
        requiresToolCalling = false,
        hasVisionInput = imageAttachment != null,
        requiresWebSearch = false,
        imageAttachment = imageAttachment,
        preferredModelId = preferredModelId
    )

    fun generateResponseStream(
        userMessage: String, 
        history: List<Message>,
        relevantMemories: List<com.example.data.model.Memory>,
        fileContext: String?,
        isIncognito: Boolean,
        requiresToolCalling: Boolean = false,
        hasVisionInput: Boolean = false,
        requiresWebSearch: Boolean = false,
        imageAttachment: ProviderImageAttachment? = null,
        preferredModelId: String? = null
    ): Flow<String> = kotlinx.coroutines.flow.flow {
        val providerHistory = history
            .filter { it.status == MessageStatus.SENT && it.content.isNotBlank() }
            .map { msg ->
                ProviderMessage(
                    role = when (msg.sender) {
                        Sender.USER -> ProviderRole.USER
                        Sender.ASSISTANT -> ProviderRole.ASSISTANT
                    },
                    content = msg.content
                )
            }

        val historyTexts = history
            .filter { it.status == MessageStatus.SENT && it.content.isNotBlank() && it.sender == Sender.USER }
            .map { it.content }

        val langContext = com.example.ai.language.LanguageDetector.determineLanguageContext(userMessage, historyTexts)
        val styleContext = com.example.ai.style.ConversationStyleDetector.determineStyleContext(userMessage, historyTexts)
        val baseInstruction = com.example.ai.style.ConversationStyleDetector.buildCombinedInstruction(langContext, styleContext)

        // Process message for persistent preference creation/updates/removals ONLY when not in Incognito
        if (!isIncognito) {
            com.example.ai.preference.CommunicationPreferenceDetector.processMessageForPreferences(userMessage)
        }

        // Combine language, style, and persistent preferences into final instruction (isolated in Incognito)
        val finalInstruction = if (isIncognito) {
            baseInstruction
        } else {
            com.example.ai.preference.CommunicationPreferenceDetector.appendPreferenceInstructions(baseInstruction)
        }

        // Resolve conversation continuity/context references
        val resolvedContext = com.example.ai.context.ConversationContextResolver.resolve(userMessage, history)

        val effectiveModelId = preferredModelId ?: selectionManager?.getGeminiModelVariant(isIncognito)?.modelId

        val request = ProviderRequest(
            userMessage = userMessage,
            history = providerHistory,
            relevantMemories = relevantMemories,
            languageHint = finalInstruction,
            conversationContext = resolvedContext,
            fileContext = fileContext,
            isIncognito = isIncognito,
            requiresToolCalling = requiresToolCalling,
            hasVisionInput = hasVisionInput || imageAttachment != null,
            requiresWebSearch = requiresWebSearch,
            imageAttachment = imageAttachment,
            preferredModelId = effectiveModelId
        )

        // Resolve primary provider and selection mode (when preferredModelId is provided by the UI, strictly lock to GEMINI)
        val forceGeminiOnly = !preferredModelId.isNullOrBlank()
        val mode = if (forceGeminiOnly) {
            com.example.ai.provider.selection.SelectionMode.MANUAL
        } else {
            selectionManager?.getSelectionMode(isIncognito) ?: com.example.ai.provider.selection.SelectionMode.AUTO
        }
        val primaryType = if (forceGeminiOnly) {
            com.example.ai.provider.AIProviderType.GEMINI
        } else if (selectionManager != null) {
            selectionManager.resolveProvider(request, isIncognito)
        } else {
            com.example.ai.provider.AIProviderType.GEMINI
        }

        if (primaryType == null) {
            val failureReason = selectionManager?.lastRoutingDecision?.reason
                ?: if (mode == com.example.ai.provider.selection.SelectionMode.MANUAL) {
                    "Manually selected provider is not available or unconfigured."
                } else {
                    "No eligible AI providers are currently available for this request."
                }
            throw com.example.ai.provider.models.ProviderException.ProviderUnavailableError(failureReason)
        }

        // Build list of providers to attempt
        val attemptList = if (mode == com.example.ai.provider.selection.SelectionMode.MANUAL) {
            listOf(primaryType) // Manual mode strictly tries primary only
        } else {
            val decisionCandidates = selectionManager?.lastRoutingDecision?.candidates
                ?.filter { it.eligible }
                ?.sortedWith(
                    compareByDescending<com.example.ai.provider.routing.RoutingCandidate> { it.score }
                        .thenBy { it.providerType.ordinal }
                )
                ?.map { it.providerType }

            if (decisionCandidates != null && decisionCandidates.isNotEmpty()) {
                val others = decisionCandidates.filter { it != primaryType }
                listOf(primaryType) + others
            } else {
                val available = selectionManager?.getAvailableProviders() ?: listOf(primaryType)
                val others = available.filter { it != primaryType }
                listOf(primaryType) + others
            }
        }

        var success = false
        var emittedMeaningful = false
        var lastError: Throwable? = null

        for ((index, providerType) in attemptList.withIndex()) {
            val startTime = timeProvider.currentTimeMillis()
            try {
                _lastUsedProvider.value = if (index > 0) "${providerType.name} (FALLBACK)" else providerType.name
                val initialModelId = if (providerType == com.example.ai.provider.AIProviderType.GEMINI) {
                    GeminiProvider.normalizeModelId(effectiveModelId)
                } else {
                    selectionManager?.metadataRegistry?.getActiveModel(providerType)?.modelId ?: providerType.name
                }
                _lastUsedModel.value = if (index > 0) "$initialModelId (Fallback)" else initialModelId

                val currentProvider = providerFactory.getProvider(providerType)
                currentProvider.generateStream(request).collect { chunk ->
                    if (!chunk.activeModelId.isNullOrBlank()) {
                        val isFallback = chunk.isModelFallback || index > 0
                        _lastUsedModel.value = if (isFallback) {
                            "${chunk.activeModelId} (Fallback)"
                        } else {
                            chunk.activeModelId
                        }
                    } else if (currentProvider is GeminiProvider) {
                        val isFallback = currentProvider.lastCallUsedFallback || index > 0
                        _lastUsedModel.value = if (isFallback) {
                            "${currentProvider.lastActiveModelId} (Fallback)"
                        } else {
                            currentProvider.lastActiveModelId
                        }
                    }
                    val textToEmit = chunk.accumulatedText.ifBlank { chunk.textDelta }
                    if (chunk.textDelta.isNotBlank() || chunk.accumulatedText.isNotBlank()) {
                        emittedMeaningful = true
                    }
                    emit(textToEmit)
                }
                val duration = timeProvider.currentTimeMillis() - startTime
                effectiveHealthTracker.recordSuccess(providerType)
                effectivePerfTracker.recordRequestSuccess(providerType, duration)

                success = true
                break
            } catch (t: Throwable) {
                val duration = timeProvider.currentTimeMillis() - startTime
                effectiveHealthTracker.recordFailure(providerType)
                effectivePerfTracker.recordRequestFailure(providerType, duration)

                lastError = t
                val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(t.localizedMessage ?: t.message ?: "Error")
                android.util.Log.w("GeminiAssistantService", "Provider ${providerType.name} failed: $safeErr")

                val isRecoverable = isRecoverableException(t)
                val hasNext = index < attemptList.size - 1

                if (mode == com.example.ai.provider.selection.SelectionMode.MANUAL || 
                    emittedMeaningful || 
                    !isRecoverable || 
                    !hasNext
                ) {
                    throw t
                }
            }
        }

        if (!success && lastError != null) {
            throw lastError
        }
    }
}
