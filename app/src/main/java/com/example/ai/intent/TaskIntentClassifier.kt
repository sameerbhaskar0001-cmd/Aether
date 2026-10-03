package com.example.ai.intent

import com.example.ai.provider.models.ProviderRequest

/**
 * Provider-neutral contract for classifying the task intent of an incoming request.
 *
 * Principles:
 * - Pure & deterministic: no network calls, no model calls, no persistence, no side effects.
 * - Explicit structured signals take highest priority.
 * - UNKNOWN is returned when intent cannot be established with high confidence.
 */
interface TaskIntentClassifier {
    /**
     * Infers the task intent and confidence from a [ProviderRequest].
     */
    fun classify(request: ProviderRequest): TaskIntentResult
}
