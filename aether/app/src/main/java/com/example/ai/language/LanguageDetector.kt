package com.example.ai.language

import java.util.Locale

enum class DetectedLanguage(val displayName: String) {
    ENGLISH("English"),
    HINDI("Hindi"),
    HINGLISH("Hinglish"),
    SPANISH("Spanish"),
    BENGALI("Bengali"),
    UNKNOWN("Unknown")
}

data class LanguageContext(
    val language: DetectedLanguage,
    val confidence: Float,
    val isExplicitOverride: Boolean = false
)

object LanguageDetector {

    private val ENGLISH_STOP_WORDS = setOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", 
        "have", "from", "hello", "hi", "hey", "thanks", "how", "what", "where", "why", "who", "your", 
        "about", "there", "their", "would", "could", "should", "which", "these", "some", "not",
        "is", "it", "in", "to", "my", "me", "we", "can", "build", "code", "today", "friend", "doing"
    )

    private val SPANISH_STOP_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "con", "para", "por", "como", "pero", "este", 
        "esta", "en", "de", "que", "es", "hola", "gracias", "buenos", "días", "cómo", "estás", 
        "hablas", "español", "quiero", "hacer", "tengo", "favor", "si", "no", "muy", "bien",
        "amigo", "amiga", "amigos", "aprender", "mi", "hoy", "mucho", "porqué", "donde"
    )

    private val HINGLISH_STOP_WORDS = setOf(
        "hai", "kya", "aur", "kaise", "main", "tum", "aap", "ko", "se", "ki", "tha", "thi", 
        "raha", "rahi", "kar", "karo", "hoga", "kab", "ab", "sath", "toh", "na", "yaar", 
        "accha", "theek", "haan", "bhai", "mera", "meri", "kuch", "nhi", "nahi", "apna", 
        "apni", "kam", "karna", "karke", "gaya", "gayi", "chahiye", "log", "chal", "rha", "rhi",
        "badhiya", "karo", "karte", "hain", "sab", "start"
    )

    /**
     * Checks if the message has an explicit language override command.
     */
    fun checkExplicitOverride(text: String): LanguageContext? {
        val normalized = text.lowercase(Locale.ROOT)
        
        return when {
            normalized.contains("reply in english") || 
            normalized.contains("respond in english") || 
            normalized.contains("write in english") ||
            normalized.contains("speak in english") -> 
                LanguageContext(DetectedLanguage.ENGLISH, 1.0f, isExplicitOverride = true)
                
            normalized.contains("reply in hindi") || 
            normalized.contains("respond in hindi") || 
            normalized.contains("write in hindi") ||
            normalized.contains("speak in hindi") ||
            normalized.contains("hindi mein") ||
            normalized.contains("hindi me") -> 
                LanguageContext(DetectedLanguage.HINDI, 1.0f, isExplicitOverride = true)
                
            normalized.contains("reply in hinglish") || 
            normalized.contains("respond in hinglish") || 
            normalized.contains("write in hinglish") ||
            normalized.contains("speak in hinglish") -> 
                LanguageContext(DetectedLanguage.HINGLISH, 1.0f, isExplicitOverride = true)
                
            normalized.contains("reply in spanish") || 
            normalized.contains("respond in spanish") || 
            normalized.contains("write in spanish") ||
            normalized.contains("speak in spanish") ||
            normalized.contains("en español") ||
            normalized.contains("en espanol") -> 
                LanguageContext(DetectedLanguage.SPANISH, 1.0f, isExplicitOverride = true)
                
            normalized.contains("reply in bengali") || 
            normalized.contains("respond in bengali") || 
            normalized.contains("write in bengali") ||
            normalized.contains("speak in bengali") ||
            normalized.contains("bengali te") -> 
                LanguageContext(DetectedLanguage.BENGALI, 1.0f, isExplicitOverride = true)
                
            else -> null
        }
    }

    /**
     * Core local deterministic heuristic language detector.
     */
    fun detect(text: String): LanguageContext {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return LanguageContext(DetectedLanguage.UNKNOWN, 0.0f)
        }

        // 1. Check for explicit overrides
        val explicit = checkExplicitOverride(trimmed)
        if (explicit != null) return explicit

        // 2. Check Unicode blocks first for non-Latin scripts
        var devanagariCount = 0
        var bengaliCount = 0
        var totalChars = 0

        for (char in trimmed) {
            if (char.isWhitespace()) continue
            totalChars++
            val code = char.code
            if (code in 0x0900..0x097F) {
                devanagariCount++
            } else if (code in 0x0980..0x09FF) {
                bengaliCount++
            }
        }

        if (totalChars > 0) {
            val devanagariRatio = devanagariCount.toFloat() / totalChars
            val bengaliRatio = bengaliCount.toFloat() / totalChars

            if (devanagariRatio > 0.3f) {
                return LanguageContext(DetectedLanguage.HINDI, devanagariRatio.coerceIn(0.5f, 1.0f))
            }
            if (bengaliRatio > 0.3f) {
                return LanguageContext(DetectedLanguage.BENGALI, bengaliRatio.coerceIn(0.5f, 1.0f))
            }
        }

        // 3. Score Latin alphabet text (including accented vowels)
        val tokens = trimmed.lowercase(Locale.ROOT)
            .split(Regex("[^a-zA-ZáéíóúüñÁÉÍÓÚÜÑ]+"))
            .filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return LanguageContext(DetectedLanguage.UNKNOWN, 0.0f)
        }

        var englishScore = 0
        var spanishScore = 0
        var hinglishScore = 0

        for (token in tokens) {
            if (ENGLISH_STOP_WORDS.contains(token)) {
                englishScore++
            }
            if (SPANISH_STOP_WORDS.contains(token)) {
                spanishScore++
            }
            if (HINGLISH_STOP_WORDS.contains(token)) {
                hinglishScore++
            }
        }

        val totalMatches = englishScore + spanishScore + hinglishScore
        if (totalMatches == 0) {
            return LanguageContext(DetectedLanguage.UNKNOWN, 0.1f)
        }

        return when {
            spanishScore > englishScore && spanishScore > hinglishScore -> {
                val confidence = (spanishScore.toFloat() / tokens.size.coerceAtLeast(1) * 1.5f).coerceIn(0.5f, 1.0f)
                LanguageContext(DetectedLanguage.SPANISH, confidence)
            }
            hinglishScore > 0 -> {
                val confidence = ((hinglishScore + englishScore).toFloat() / tokens.size.coerceAtLeast(1) * 1.5f).coerceIn(0.5f, 1.0f)
                LanguageContext(DetectedLanguage.HINGLISH, confidence)
            }
            englishScore > 0 -> {
                val confidence = (englishScore.toFloat() / tokens.size.coerceAtLeast(1) * 1.5f).coerceIn(0.5f, 1.0f)
                LanguageContext(DetectedLanguage.ENGLISH, confidence)
            }
            else -> {
                LanguageContext(DetectedLanguage.UNKNOWN, 0.1f)
            }
        }
    }

    /**
     * Determines language context falling back to history if confidence is low.
     */
    fun determineLanguageContext(userMessage: String, history: List<String> = emptyList()): LanguageContext {
        val currentContext = detect(userMessage)
        if (currentContext.isExplicitOverride || currentContext.confidence >= 0.4f) {
            return currentContext
        }

        for (msg in history.reversed()) {
            val histContext = detect(msg)
            if (histContext.language != DetectedLanguage.UNKNOWN && histContext.confidence >= 0.4f) {
                return histContext.copy(confidence = histContext.confidence * 0.8f)
            }
        }

        return currentContext
    }

    /**
     * Builds concise AI provider prompt instructions matching the language.
     */
    fun buildLanguageInstruction(context: LanguageContext): String? {
        return when (context.language) {
            DetectedLanguage.ENGLISH -> 
                "You must respond in English. Match the user's natural language style."
            DetectedLanguage.HINDI -> 
                "You must respond in Hindi (हिंदी). Match the user's natural language style."
            DetectedLanguage.HINGLISH -> 
                "You must respond in Hinglish (a natural blend of Hindi and English written in Latin script), matching the user's conversational, code-switching tone."
            DetectedLanguage.SPANISH -> 
                "You must respond in Spanish (Español). Match the user's natural language style."
            DetectedLanguage.BENGALI -> 
                "You must respond in Bengali (বাংলা). Match the user's natural language style."
            DetectedLanguage.UNKNOWN -> null
        }
    }
}
