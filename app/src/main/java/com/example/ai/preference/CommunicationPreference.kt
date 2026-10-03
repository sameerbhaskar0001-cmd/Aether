package com.example.ai.preference

enum class PreferenceCategory {
    RESPONSE_LENGTH,
    EXPLANATION_DEPTH,
    TONE,
    FORMATTING,
    TECHNICAL_EXPLANATION_LEVEL
}

data class CommunicationPreference(
    val category: PreferenceCategory,
    val value: String,
    val description: String
)
