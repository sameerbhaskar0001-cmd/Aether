package com.example.ai.intent

/**
 * Result of task intent inference on a request.
 * Contains the primary inferred intent, optional secondary intents, a confidence score (0.0 to 1.0),
 * and structured reasoning signals explaining why the intent was established.
 */
data class TaskIntentResult(
    val primaryIntent: TaskIntent,
    val secondaryIntents: List<TaskIntent> = emptyList(),
    val confidence: Float,
    val reasoningSignals: List<String> = emptyList()
)
