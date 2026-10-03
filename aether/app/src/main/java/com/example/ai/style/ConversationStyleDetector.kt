package com.example.ai.style

import java.util.Locale

enum class ConversationStyle {
    CASUAL,
    PROFESSIONAL,
    TECHNICAL,
    BEGINNER_FRIENDLY,
    CONCISE,
    DETAILED,
    UNKNOWN
}

data class ConversationStyleContext(
    val primaryStyle: ConversationStyle,
    val additionalStyles: Set<ConversationStyle> = emptySet(),
    val isExplicitOverride: Boolean = false
)

object ConversationStyleDetector {

    /**
     * Checks if the message has an explicit style override command.
     */
    fun checkExplicitOverrides(text: String): ConversationStyleContext? {
        val normalized = text.lowercase(Locale.ROOT)
        val styles = mutableSetOf<ConversationStyle>()
        
        if (normalized.contains("explain simply") || 
            normalized.contains("explain like i'm five") || 
            normalized.contains("explain like im 5") || 
            normalized.contains("beginner friendly") || 
            normalized.contains("for beginners") || 
            normalized.contains("simple terms") ||
            normalized.contains("easy terms")
        ) {
            styles.add(ConversationStyle.BEGINNER_FRIENDLY)
        }
        
        if (normalized.contains("be detailed") || 
            normalized.contains("give a detailed explanation") || 
            normalized.contains("explain in detail") || 
            normalized.contains("elaborate") || 
            normalized.contains("long response") || 
            normalized.contains("verbose") || 
            normalized.contains("comprehensive")
        ) {
            styles.add(ConversationStyle.DETAILED)
        }
        
        if (normalized.contains("keep it short") || 
            normalized.contains("be concise") || 
            normalized.contains("briefly") || 
            normalized.contains("short answer") || 
            normalized.contains("in one sentence") || 
            normalized.contains("in a few words") || 
            normalized.contains("no yapping")
        ) {
            styles.add(ConversationStyle.CONCISE)
        }
        
        if (normalized.contains("be professional") || 
            normalized.contains("professional tone") || 
            normalized.contains("formal") || 
            normalized.contains("corporate tone")
        ) {
            styles.add(ConversationStyle.PROFESSIONAL)
        }
        
        if (normalized.contains("be technical") || 
            normalized.contains("technical explanation") || 
            normalized.contains("with code") || 
            normalized.contains("under the hood")
        ) {
            styles.add(ConversationStyle.TECHNICAL)
        }
        
        if (normalized.contains("be casual") || 
            normalized.contains("casual tone") || 
            normalized.contains("chill") || 
            normalized.contains("informal")
        ) {
            styles.add(ConversationStyle.CASUAL)
        }
        
        if (styles.isNotEmpty()) {
            val primary = styles.first()
            val additional = styles.drop(1).toSet()
            return ConversationStyleContext(primary, additional, isExplicitOverride = true)
        }
        
        return null
    }

    /**
     * Core local deterministic heuristic style detector.
     */
    fun detect(text: String): ConversationStyleContext {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ConversationStyleContext(ConversationStyle.UNKNOWN)
        }

        // 1. Check for explicit overrides
        val explicit = checkExplicitOverrides(trimmed)
        if (explicit != null) return explicit

        // 2. Score text token signals
        val normalized = trimmed.lowercase(Locale.ROOT)
        val tokens = normalized.split(Regex("[^a-zA-Z]+")).filter { it.isNotEmpty() }.toSet()
        
        val technicalKeywords = setOf(
            "code", "function", "class", "database", "api", "json", "compile", "xml", 
            "gradle", "kotlin", "java", "python", "git", "query", "sql", "exception", 
            "error", "stacktrace", "framework", "variable", "loop", "array", "thread", "sdk"
        )
        
        val professionalKeywords = setOf(
            "dear", "sincerely", "regarding", "please find attached", "thank you for", 
            "best regards", "kind regards", "as per", "business", "colleague", "deliverable",
            "synergy", "project", "schedule", "meeting", "request", "appreciate", "formal"
        )
        
        val casualKeywords = setOf(
            "hey", "hi", "sup", "yo", "dude", "chill", "bro", "lol", "lmao", "wtf", 
            "pls", "thx", "gonna", "wanna", "bhai", "yaar", "cool", "awesome", "nice",
            "ok", "okay", "yeah", "nope", "yep"
        )
        
        val beginnerKeywords = setOf(
            "newbie", "beginner", "learn"
        )
        
        var technicalScore = 0
        var professionalScore = 0
        var casualScore = 0
        var beginnerScore = 0
        
        for (token in tokens) {
            if (technicalKeywords.contains(token)) technicalScore++
            if (professionalKeywords.contains(token)) professionalScore++
            if (casualKeywords.contains(token)) casualScore++
            if (beginnerKeywords.contains(token)) beginnerScore++
        }
        
        // Match multi-word sequences
        if (normalized.contains("how do i") || normalized.contains("how to start") || normalized.contains("what is")) {
            beginnerScore += 2
        }
        if (normalized.contains("thank you") || normalized.contains("kind regards") || normalized.contains("best regards")) {
            professionalScore += 2
        }
        if (normalized.contains("```") || normalized.contains("`")) {
            technicalScore += 3
        }
        
        val scoredStyles = mutableListOf<Pair<ConversationStyle, Int>>()
        if (technicalScore > 0) scoredStyles.add(ConversationStyle.TECHNICAL to technicalScore)
        if (professionalScore > 0) scoredStyles.add(ConversationStyle.PROFESSIONAL to professionalScore)
        if (casualScore > 0) scoredStyles.add(ConversationStyle.CASUAL to casualScore)
        if (beginnerScore > 0) scoredStyles.add(ConversationStyle.BEGINNER_FRIENDLY to beginnerScore)
        
        // Sort descending by score
        val sorted = scoredStyles.sortedByDescending { it.second }
        
        // Consider length indicators as compatible styles
        val length = trimmed.length
        val lengthStyle = when {
            length < 20 -> ConversationStyle.CONCISE
            length > 250 -> ConversationStyle.DETAILED
            else -> null
        }
        
        val primary: ConversationStyle
        val additional = mutableSetOf<ConversationStyle>()

        val highestScored = sorted.firstOrNull()
        if (lengthStyle != null && (highestScored == null || highestScored.second <= 1)) {
            primary = lengthStyle
            if (highestScored != null) {
                additional.addAll(sorted.map { it.first })
            }
        } else if (highestScored != null) {
            primary = highestScored.first
            additional.addAll(sorted.drop(1).map { it.first })
            if (lengthStyle != null && lengthStyle != primary) {
                additional.add(lengthStyle)
            }
        } else {
            primary = ConversationStyle.UNKNOWN
        }
        
        return ConversationStyleContext(primary, additional)
    }

    /**
     * Determines style context falling back to history if current message is tone-neutral or UNKNOWN.
     */
    fun determineStyleContext(userMessage: String, history: List<String> = emptyList()): ConversationStyleContext {
        val currentContext = detect(userMessage)
        if (currentContext.isExplicitOverride) {
            return currentContext
        }
        
        val toneStyles = setOf(ConversationStyle.CASUAL, ConversationStyle.PROFESSIONAL, ConversationStyle.TECHNICAL, ConversationStyle.BEGINNER_FRIENDLY)
        val hasTone = toneStyles.contains(currentContext.primaryStyle) || currentContext.additionalStyles.any { toneStyles.contains(it) }
        
        if (hasTone) {
            return currentContext
        }
        
        for (msg in history.reversed()) {
            val histContext = detect(msg)
            val histTone = when {
                toneStyles.contains(histContext.primaryStyle) -> histContext.primaryStyle
                else -> histContext.additionalStyles.firstOrNull { toneStyles.contains(it) }
            }
            if (histTone != null) {
                val newAdditional = currentContext.additionalStyles.toMutableSet()
                if (currentContext.primaryStyle != ConversationStyle.UNKNOWN) {
                    newAdditional.add(currentContext.primaryStyle)
                }
                newAdditional.addAll(histContext.additionalStyles)
                return ConversationStyleContext(
                    primaryStyle = histTone,
                    additionalStyles = newAdditional
                )
            }
        }
        
        return currentContext
    }

    /**
     * Combines the existing language instruction with the ConversationStyle guidelines.
     */
    fun buildCombinedInstruction(
        langContext: com.example.ai.language.LanguageContext,
        styleContext: ConversationStyleContext
    ): String? {
        val langInstruction = com.example.ai.language.LanguageDetector.buildLanguageInstruction(langContext)
        
        val styleParts = mutableListOf<String>()
        val allStyles = LinkedHashSet<ConversationStyle>().apply {
            if (styleContext.primaryStyle != ConversationStyle.UNKNOWN) {
                add(styleContext.primaryStyle)
            }
            addAll(styleContext.additionalStyles.filter { it != ConversationStyle.UNKNOWN })
        }
        
        for (style in allStyles) {
            when (style) {
                ConversationStyle.CONCISE -> styleParts.add("Keep your response extremely concise, brief, and to the point.")
                ConversationStyle.DETAILED -> styleParts.add("Provide a highly detailed, comprehensive, and thorough explanation.")
                ConversationStyle.BEGINNER_FRIENDLY -> styleParts.add("Explain the concepts simply using clear, beginner-friendly terms, avoiding jargon.")
                ConversationStyle.TECHNICAL -> styleParts.add("Maintain a technical, precise tone. If appropriate, include clean, well-commented code snippets.")
                ConversationStyle.PROFESSIONAL -> styleParts.add("Maintain a highly professional, polite, and formal business tone.")
                ConversationStyle.CASUAL -> styleParts.add("Maintain a casual, friendly, and conversational tone.")
                ConversationStyle.UNKNOWN -> { /* no-op */ }
            }
        }
        
        val styleInstruction = if (styleParts.isNotEmpty()) styleParts.joinToString(" ") else null
        
        return when {
            langInstruction != null && styleInstruction != null -> 
                "$langInstruction\n\nStyle guidelines: $styleInstruction"
            langInstruction != null -> langInstruction
            styleInstruction != null -> "Style guidelines: $styleInstruction"
            else -> null
        }
    }
}
