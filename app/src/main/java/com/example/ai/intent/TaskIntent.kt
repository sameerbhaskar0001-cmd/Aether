package com.example.ai.intent

/**
 * Provider-neutral domain representation of task intent inferred from user requests.
 * Used by Aether's intent analysis layer to guide downstream routing policies cleanly.
 */
enum class TaskIntent {
    GENERAL_CHAT,
    KNOWLEDGE_QUESTION,
    CALCULATION,
    DATE_TIME,
    UNIT_CONVERSION,
    WEATHER,
    PRODUCTIVITY,
    FILE_ANALYSIS,
    WEB_RESEARCH,
    VISION_ANALYSIS,
    CODE,
    UNKNOWN
}
