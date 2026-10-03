package com.example.ai.proactive

data class ProactivePreferences(
    val isEnabled: Boolean = true,
    val isQuietMode: Boolean = false,
    val cooldownDurationMs: Long = 300000L, // 5 minutes default
    val allowedCategories: Set<String> = setOf(
        "Goals", "Projects", "Interests", "Education", "Upcoming Events", "Events", "Tasks"
    ),
    val sensitivityThreshold: Double = 0.5
)
