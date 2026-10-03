package com.example.ai.provider.routing

import com.example.ai.intent.DefaultTaskIntentClassifier
import com.example.ai.intent.TaskIntent
import com.example.ai.intent.TaskIntentClassifier
import com.example.ai.provider.models.ProviderRequest

/**
 * Deterministic, provider-neutral implementation of [TaskRequirementExtractor].
 * Converts an incoming [ProviderRequest] into a structured [RoutingTaskProfile].
 *
 * Integrates [TaskIntentClassifier] to derive intent-based requirements while respecting:
 * - Explicit request flags (requiresToolCalling, hasVisionInput, requiresWebSearch) have highest priority.
 * - Intent classification maps conservatively:
 *   - VISION_ANALYSIS -> requiresVision = true (if confidence >= 0.8f)
 *   - WEB_RESEARCH -> requiresWebSearch = true (if confidence >= 0.8f)
 *   - FILE_ANALYSIS -> captures intent, but MUST NOT automatically set requiresToolCalling = true
 *   - CALCULATION / DATE_TIME / UNIT_CONVERSION / WEATHER / PRODUCTIVITY / CODE / GENERAL_CHAT / KNOWLEDGE_QUESTION / UNKNOWN ->
 *     preserve existing tool architecture, no invented capability requirements.
 * - Pure, deterministic, in-memory execution without network calls or persistence.
 */
class DefaultTaskRequirementExtractor(
    private val longContextThresholdTokens: Int = DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS,
    private val intentClassifier: TaskIntentClassifier = DefaultTaskIntentClassifier()
) : TaskRequirementExtractor {

    companion object {
        const val DEFAULT_LONG_CONTEXT_THRESHOLD_TOKENS = 8000
        const val CHARS_PER_TOKEN = 4
        const val MIN_INTENT_CONFIDENCE_THRESHOLD = 0.80f
    }

    override fun extract(request: ProviderRequest): RoutingTaskProfile {
        val totalChars = request.userMessage.length +
                request.history.sumOf { it.content.length } +
                (request.fileContext?.length ?: 0) +
                request.relevantMemories.sumOf { it.content.length }

        val estimatedTokens = if (totalChars > 0) {
            (totalChars / CHARS_PER_TOKEN).coerceAtLeast(1)
        } else {
            0
        }

        val requiresLongContext = estimatedTokens > longContextThresholdTokens

        // Classify task intent
        val intentResult = intentClassifier.classify(request)

        // Intent-derived requirements mapped conservatively
        val intentRequiresVision = intentResult.primaryIntent == TaskIntent.VISION_ANALYSIS &&
                intentResult.confidence >= MIN_INTENT_CONFIDENCE_THRESHOLD

        val intentRequiresWebSearch = intentResult.primaryIntent == TaskIntent.WEB_RESEARCH &&
                intentResult.confidence >= MIN_INTENT_CONFIDENCE_THRESHOLD

        // Explicit request flags remain authoritative and take precedence
        val finalRequiresVision = request.hasVisionInput || intentRequiresVision
        val finalRequiresWebSearch = request.requiresWebSearch || intentRequiresWebSearch

        // File analysis or other intents MUST NOT automatically imply tool calling
        val finalRequiresToolCalling = request.requiresToolCalling

        return RoutingTaskProfile(
            requiresLongContext = requiresLongContext,
            requiresToolCalling = finalRequiresToolCalling,
            requiresVision = finalRequiresVision,
            requiresWebSearch = finalRequiresWebSearch,
            estimatedInputTokens = estimatedTokens,
            userPreferredProvider = request.userPreferredProvider,
            inferredIntent = intentResult
        )
    }
}
