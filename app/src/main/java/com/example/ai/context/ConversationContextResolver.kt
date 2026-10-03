package com.example.ai.context

import java.util.Locale

object ConversationContextResolver {

    private const val MAX_RECENT_MESSAGES = 4
    private const val MAX_TOTAL_CHARACTERS = 1000

    /**
     * Resolves references in the current user message using bounded conversation history.
     * History should be ordered chronologically (oldest to newest).
     */
    fun resolve(
        userMessage: String,
        history: List<com.example.data.model.Message>
    ): ConversationContext {
        val trimmedMsg = userMessage.trim()
        if (trimmedMsg.isEmpty()) {
            return ConversationContext(null, 0.0f, null, false)
        }

        // Apply strict safety limits to conversation history
        val boundedHistory = history.takeLast(MAX_RECENT_MESSAGES)
            .filter { it.content.length <= MAX_TOTAL_CHARACTERS }
        
        val normalizedMsg = trimmedMsg.lowercase(Locale.ROOT)
        // Clean trailing punctuation
        val cleanedMsg = normalizedMsg.replace(Regex("[.!?\\s]+$"), "")

        // 1. Detect explicit topic changes
        val isExplicitTopicChange = cleanedMsg.contains("switch to") || 
                cleanedMsg.contains("different topic") || 
                cleanedMsg.contains("new topic") || 
                cleanedMsg.contains("change the subject") || 
                cleanedMsg.contains("anyway") ||
                cleanedMsg.contains("forget that")

        if (isExplicitTopicChange) {
            return ConversationContext(
                resolvedContextText = null,
                confidence = 0.0f,
                detectedTopic = extractPotentialTopic(trimmedMsg),
                isResolved = false
            )
        }

        // 2. Resolve numbered option references (e.g. "second option", "option 2")
        val numberedMatch = extractNumberedIndex(cleanedMsg)
        if (numberedMatch != null) {
            val lastAssistantMsg = boundedHistory.lastOrNull { it.sender == com.example.data.model.Sender.ASSISTANT }
            if (lastAssistantMsg != null) {
                val resolvedOption = parseNumberedOption(lastAssistantMsg.content, numberedMatch)
                if (resolvedOption != null) {
                    return ConversationContext(
                        resolvedContextText = resolvedOption,
                        confidence = 1.0f,
                        detectedTopic = resolvedOption,
                        isResolved = true
                    )
                } else {
                    // Numbered option referenced but not found: fail closed (unresolved)
                    return ConversationContext(null, 0.0f, null, false)
                }
            }
        }

        // 3. Detect ambiguous references with multiple candidates
        if (isAmbiguousReference(cleanedMsg, boundedHistory)) {
            return ConversationContext(null, 0.0f, null, false)
        }

        // 4. Resolve simple references ("this", "that", "it", "this one")
        if (cleanedMsg.contains("this one") || 
            cleanedMsg == "this" || 
            cleanedMsg == "that" || 
            cleanedMsg == "it" ||
            cleanedMsg.startsWith("tell me about it") ||
            cleanedMsg.startsWith("tell me more about it") ||
            cleanedMsg.startsWith("explain it") ||
            cleanedMsg.startsWith("explain that") ||
            cleanedMsg.startsWith("what about it") ||
            cleanedMsg.startsWith("what is it")
        ) {
            val lastAssistantMsg = boundedHistory.lastOrNull { it.sender == com.example.data.model.Sender.ASSISTANT }
            if (lastAssistantMsg != null) {
                return ConversationContext(
                    resolvedContextText = lastAssistantMsg.content,
                    confidence = 0.9f,
                    detectedTopic = extractPOTTopicFromText(lastAssistantMsg.content),
                    isResolved = true
                )
            }
        }

        // 5. "the previous one"
        if (cleanedMsg.contains("previous one") || cleanedMsg.contains("previous option")) {
            val assistantMessages = boundedHistory.filter { it.sender == com.example.data.model.Sender.ASSISTANT }
            if (assistantMessages.size >= 2) {
                val prevMsg = assistantMessages[assistantMessages.size - 2]
                return ConversationContext(
                    resolvedContextText = prevMsg.content,
                    confidence = 0.9f,
                    detectedTopic = extractPOTTopicFromText(prevMsg.content),
                    isResolved = true
                )
            }
        }

        // 6. "same project"
        if (cleanedMsg.contains("same project")) {
            val projectMention = boundedHistory.reversed()
                .map { extractProjectMention(it.content) }
                .firstOrNull { it != null }
            if (projectMention != null) {
                return ConversationContext(
                    resolvedContextText = "Project: $projectMention",
                    confidence = 0.95f,
                    detectedTopic = projectMention,
                    isResolved = true
                )
            }
        }

        // 7. "that approach"
        if (cleanedMsg.contains("that approach")) {
            val approachMention = boundedHistory.lastOrNull { it.sender == com.example.data.model.Sender.ASSISTANT }
            if (approachMention != null) {
                return ConversationContext(
                    resolvedContextText = approachMention.content,
                    confidence = 0.85f,
                    detectedTopic = "Approach",
                    isResolved = true
                )
            }
        }

        // 8. "what we discussed earlier"
        if (cleanedMsg.contains("discussed earlier") || cleanedMsg.contains("discussed before")) {
            val earlierUserMsg = boundedHistory.firstOrNull { it.sender == com.example.data.model.Sender.USER }
            if (earlierUserMsg != null) {
                return ConversationContext(
                    resolvedContextText = earlierUserMsg.content,
                    confidence = 0.8f,
                    detectedTopic = extractPOTTopicFromText(earlierUserMsg.content),
                    isResolved = true
                )
            }
        }

        // 9. "continue from there"
        if (cleanedMsg.contains("continue from there") || cleanedMsg.contains("go on from there")) {
            val lastAssistantMsg = boundedHistory.lastOrNull { it.sender == com.example.data.model.Sender.ASSISTANT }
            if (lastAssistantMsg != null) {
                return ConversationContext(
                    resolvedContextText = lastAssistantMsg.content,
                    confidence = 0.85f,
                    detectedTopic = "Continuation",
                    isResolved = true
                )
            }
        }

        // 10. Topic continuity detection (no explicit continuity keywords, check word overlap)
        val lastUserMsg = boundedHistory.lastOrNull { it.sender == com.example.data.model.Sender.USER }
        if (lastUserMsg != null) {
            val hasOverlap = checkWordOverlap(cleanedMsg, lastUserMsg.content.lowercase(Locale.ROOT))
            if (hasOverlap) {
                return ConversationContext(
                    resolvedContextText = lastUserMsg.content,
                    confidence = 0.7f,
                    detectedTopic = extractPOTTopicFromText(lastUserMsg.content),
                    isResolved = true
                )
            }
        }

        return ConversationContext(null, 0.0f, null, false)
    }

    private fun extractPotentialTopic(text: String): String? {
        val keywords = listOf("about ", "topic: ", "on ")
        val lowerText = text.lowercase(Locale.ROOT)
        for (keyword in keywords) {
            val index = lowerText.indexOf(keyword)
            if (index != -1 && index + keyword.length < text.length) {
                return text.substring(index + keyword.length).trim()
            }
        }
        return null
    }

    private fun extractNumberedIndex(text: String): Int? {
        return when {
            text.contains("first option") || text.contains("option 1") || text.contains("the first one") -> 1
            text.contains("second option") || text.contains("option 2") || text.contains("the second one") -> 2
            text.contains("third option") || text.contains("option 3") || text.contains("the third one") -> 3
            else -> null
        }
    }

    private fun parseNumberedOption(text: String, optionIndex: Int): String? {
        val lines = text.lines()
        val numberedPatterns = listOf(
            Regex("^\\s*${optionIndex}[.)]\\s*(.*)", RegexOption.IGNORE_CASE),
            Regex("^\\s*-\\s*(.*)", RegexOption.IGNORE_CASE) // simple dash
        )

        var count = 0
        for (line in lines) {
            val trimmedLine = line.trim()
            for (pattern in numberedPatterns) {
                val match = pattern.find(trimmedLine)
                if (match != null) {
                    if (pattern.pattern.contains(optionIndex.toString())) {
                        return match.groupValues[1].trim()
                    } else if (trimmedLine.startsWith("-")) {
                        count++
                        if (count == optionIndex) {
                            return match.groupValues[1].trim()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isAmbiguousReference(text: String, history: List<com.example.data.model.Message>): Boolean {
        val cleaned = text.replace(Regex("[.!?]+$"), "")
        if (!(cleaned == "it" || cleaned == "this" || cleaned == "that" || cleaned.contains("tell me about it") || cleaned.contains("tell me more about it"))) {
            return false
        }
        val lastAssistant = history.lastOrNull { it.sender == com.example.data.model.Sender.ASSISTANT } ?: return false
        val content = lastAssistant.content.lowercase(Locale.ROOT)
        
        // If text lists multiple clear tech options or databases, e.g. "room or sharedpreferences"
        if (content.contains(" or ") || content.contains(" vs ")) {
            val optionCount = countOccurrences(content, "room") + 
                    countOccurrences(content, "sharedpreferences") +
                    countOccurrences(content, "database") +
                    countOccurrences(content, "preferences")
            return optionCount >= 2
        }
        return false
    }

    private fun countOccurrences(text: String, word: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(word, index)
            if (index == -1) break
            count++
            index += word.length
        }
        return count
    }

    private fun extractProjectMention(text: String): String? {
        val regex = Regex("project (\\w+)", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun extractPOTTopicFromText(text: String): String? {
        val words = text.split(" ").filter { it.length > 5 }.take(2)
        return if (words.isNotEmpty()) words.joinToString(" ") else null
    }

    private fun checkWordOverlap(text1: String, text2: String): Boolean {
        val stopWords = setOf("the", "and", "a", "of", "to", "in", "is", "that", "it")
        val words1 = text1.split(Regex("\\W+")).filter { it.length > 3 && !stopWords.contains(it) }.toSet()
        val words2 = text2.split(Regex("\\W+")).filter { it.length > 3 && !stopWords.contains(it) }.toSet()
        return words1.intersect(words2).isNotEmpty()
    }
}
