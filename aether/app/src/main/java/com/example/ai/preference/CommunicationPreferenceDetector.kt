package com.example.ai.preference

import java.util.Locale
import java.util.regex.Pattern

object CommunicationPreferenceDetector {

    private val PERSISTENCE_INDICATORS = setOf(
        "from now on", "always", "remember that", "prefer", "every time", 
        "for all future", "save my preference", "save preference", "consistently",
        "from this point", "starting now"
    )

    private val SENSITIVE_PATTERNS = listOf(
        Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), // email
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), // ssn
        Pattern.compile("\\b\\d{10,16}\\b"), // phone or card numbers
        Pattern.compile("\\b(password|secret|pin|bank|credit card|ssn|social security|email is|my phone is)\\b", Pattern.CASE_INSENSITIVE)
    )

    private val PERSONALITY_TRAITS = setOf(
        "anxious", "impatient", "introverted", "extroverted", "sad", "depressed", 
        "lazy", "smart", "dumb", "angry", "happy", "sensitive", "stubborn", "shy"
    )

    /**
     * Checks if the user message contains explicit language requesting preference persistence.
     */
    fun hasPersistenceLanguage(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        return PERSISTENCE_INDICATORS.any { normalized.contains(it) }
    }

    /**
     * Scans for explicit request to remove/clear/forget a preference.
     * Returns the category to remove, or null if none detected.
     */
    fun detectRemovalRequest(text: String): PreferenceCategory? {
        val normalized = text.lowercase(Locale.ROOT)
        
        val isRemovalIntent = normalized.contains("forget my preference") || 
                normalized.contains("stop keeping") || 
                normalized.contains("stop explaining") || 
                normalized.contains("clear my") || 
                normalized.contains("delete my") || 
                normalized.contains("remove my") || 
                normalized.contains("forget that") || 
                normalized.contains("no longer prefer") ||
                normalized.contains("forget always")

        if (!isRemovalIntent) return null

        return when {
            normalized.contains("length") || normalized.contains("concise") || normalized.contains("detailed") || normalized.contains("short") || normalized.contains("long") -> 
                PreferenceCategory.RESPONSE_LENGTH
            normalized.contains("depth") || normalized.contains("simple") || normalized.contains("beginner") || normalized.contains("expert") || normalized.contains("advanced") -> 
                PreferenceCategory.EXPLANATION_DEPTH
            normalized.contains("tone") || normalized.contains("casual") || normalized.contains("professional") || normalized.contains("formal") -> 
                PreferenceCategory.TONE
            normalized.contains("formatting") || normalized.contains("bullet points") || normalized.contains("list") || normalized.contains("markdown") -> 
                PreferenceCategory.FORMATTING
            normalized.contains("technical") || normalized.contains("code") || normalized.contains("conceptual") -> 
                PreferenceCategory.TECHNICAL_EXPLANATION_LEVEL
            else -> null
        }
    }

    /**
     * Scans and returns a potential CommunicationPreference if explicitly requested,
     * or null if it lacks persistence language, contains sensitive info, or personality traits.
     */
    fun detectPreference(text: String): CommunicationPreference? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Must have persistence language
        if (!hasPersistenceLanguage(trimmed)) return null

        val normalized = trimmed.lowercase(Locale.ROOT)

        // Prevent sensitive personal information
        for (pattern in SENSITIVE_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return null
            }
        }

        // Prevent inferred personality traits
        if (PERSONALITY_TRAITS.any { normalized.contains(it) }) {
            return null
        }

        // 1. RESPONSE_LENGTH
        if (normalized.contains("concise") || normalized.contains("keep it short") || normalized.contains("short answers") || normalized.contains("no yapping") || normalized.contains("brief")) {
            return CommunicationPreference(
                PreferenceCategory.RESPONSE_LENGTH,
                "CONCISE",
                "Keep your response extremely concise, brief, and to the point."
            )
        }
        if (normalized.contains("detailed") || normalized.contains("elaborate") || normalized.contains("comprehensive") || normalized.contains("thorough") || normalized.contains("long answers")) {
            return CommunicationPreference(
                PreferenceCategory.RESPONSE_LENGTH,
                "DETAILED",
                "Provide a highly detailed, comprehensive, and thorough explanation."
            )
        }

        // 2. EXPLANATION_DEPTH
        if (normalized.contains("simple") || normalized.contains("beginner-friendly") || normalized.contains("for beginners") || normalized.contains("like i'm five") || normalized.contains("easy terms")) {
            return CommunicationPreference(
                PreferenceCategory.EXPLANATION_DEPTH,
                "BEGINNER",
                "Explain the concepts simply using clear, beginner-friendly terms, avoiding jargon."
            )
        }
        if (normalized.contains("advanced") || normalized.contains("expert") || normalized.contains("deep dive")) {
            return CommunicationPreference(
                PreferenceCategory.EXPLANATION_DEPTH,
                "ADVANCED",
                "Explain concepts with deep technical depth, targeted at an advanced or expert level."
            )
        }

        // 3. TONE
        if (normalized.contains("casual") || normalized.contains("chill") || normalized.contains("informal")) {
            return CommunicationPreference(
                PreferenceCategory.TONE,
                "CASUAL",
                "Maintain a casual, friendly, and conversational tone."
            )
        }
        if (normalized.contains("professional") || normalized.contains("formal") || normalized.contains("corporate tone")) {
            return CommunicationPreference(
                PreferenceCategory.TONE,
                "PROFESSIONAL",
                "Maintain a highly professional, polite, and formal business tone."
            )
        }

        // 4. FORMATTING
        if (normalized.contains("bullet points") || normalized.contains("bullets") || normalized.contains("lists")) {
            return CommunicationPreference(
                PreferenceCategory.FORMATTING,
                "BULLET_POINTS",
                "Always format and present structural information using clear bullet points."
            )
        }
        if (normalized.contains("plain text") || normalized.contains("no formatting")) {
            return CommunicationPreference(
                PreferenceCategory.FORMATTING,
                "PLAIN_TEXT",
                "Avoid complex layout components and return responses as simple, plain text."
            )
        }

        // 5. TECHNICAL_EXPLANATION_LEVEL
        if (normalized.contains("with code") || normalized.contains("always explain code") || normalized.contains("code snippets")) {
            return CommunicationPreference(
                PreferenceCategory.TECHNICAL_EXPLANATION_LEVEL,
                "WITH_CODE",
                "Incorporate complete, clean, and well-commented code snippets in all technical explanations."
            )
        }
        if (normalized.contains("conceptual") || normalized.contains("no code")) {
            return CommunicationPreference(
                PreferenceCategory.TECHNICAL_EXPLANATION_LEVEL,
                "CONCEPTUAL",
                "Provide high-level conceptual explanations without showing programming code."
            )
        }

        return null
    }

    /**
     * Process message to check if it contains a preference to save or a preference to remove.
     * Executes the corresponding action against the Store.
     */
    fun processMessageForPreferences(text: String) {
        val removalCategory = detectRemovalRequest(text)
        if (removalCategory != null) {
            CommunicationPreferenceStore.removePreference(removalCategory)
            return
        }

        val pref = detectPreference(text)
        if (pref != null) {
            CommunicationPreferenceStore.savePreference(pref)
        }
    }

    /**
     * Appends instructions based on active CommunicationPreferences.
     */
    fun appendPreferenceInstructions(baseInstruction: String?): String? {
        val allPrefs = CommunicationPreferenceStore.getAllPreferences()
        if (allPrefs.isEmpty()) return baseInstruction

        val prefGuidelines = allPrefs.joinToString(" ") { it.description }
        val prefix = "Communication Preference Guidelines: $prefGuidelines"

        return when {
            baseInstruction != null -> "$baseInstruction\n\n$prefix"
            else -> prefix
        }
    }
}
