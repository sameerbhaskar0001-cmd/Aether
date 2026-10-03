package com.example.ai.proactive

import com.example.ai.SystemTimeProvider
import com.example.ai.TimeProvider
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.Sender

class ProactiveTriggerPipeline(
    val config: ProactiveContextConfig = ProactiveContextConfig(),
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    private var lastEvaluatedContextHash: Int? = null
    private var lastEvaluationTimeMs: Long = 0L

    companion object {
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "is", "are", "was", "were",
            "be", "been", "being", "to", "from", "in", "on", "at", "by", "for", "with", "about",
            "against", "between", "into", "through", "during", "before", "after", "above", "below",
            "up", "down", "off", "over", "under", "again", "further", "once", "here", "there",
            "when", "where", "why", "how", "all", "any", "both", "each", "few", "more", "most",
            "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "can", "will", "just", "should", "now", "i", "my", "me", "we", "our",
            "us", "you", "your", "he", "she", "it", "they", "them", "also", "need", "want"
        )
    }

    /**
     * Checks if a message string is a trivial filler or short acknowledgement.
     */
    fun isTrivialMessage(content: String): Boolean {
        val trimmed = content.trim().lowercase()
        if (trimmed.isEmpty()) return true

        // Clean punctuation
        val cleaned = trimmed.replace(Regex("[^a-z0-9\\s]"), "").trim()
        if (cleaned.isEmpty()) return true

        // Direct match with trivial keywords
        if (config.trivialKeywords.contains(cleaned)) {
            return true
        }

        // Tokenized check for short multi-word trivial phrases ("ok thanks", "sure thing")
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2 && words.all { config.trivialKeywords.contains(it) }) {
            return true
        }

        return false
    }

    /**
     * Extracts meaningful non-stopword tokens from a text string.
     */
    fun extractMeaningfulTokens(text: String): Set<String> {
        val cleaned = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "")
        return cleaned.split(Regex("\\s+"))
            .filter { token -> token.length >= 2 && !STOPWORDS.contains(token) && !config.trivialKeywords.contains(token) }
            .toSet()
    }

    /**
     * Detects if a new user message represents a topic change from previous conversation context.
     */
    fun detectTopicChange(history: List<Message>, newMessage: String): Boolean {
        if (isTrivialMessage(newMessage)) return false

        val trimmedLower = newMessage.trim().lowercase()
        val continuationPrefixes = setOf("and", "also", "plus", "additionally", "furthermore", "besides", "too", "as well")
        val firstWord = trimmedLower.split(Regex("\\s+")).firstOrNull() ?: ""
        if (continuationPrefixes.contains(firstWord)) {
            return false
        }

        val newTokens = extractMeaningfulTokens(newMessage)
        if (newTokens.size < config.minMeaningfulWordCount) return false

        // Extract prior meaningful context from recent messages (ignoring the new message if present in history)
        val priorUserMessages = history.filter { 
            it.sender == Sender.USER && 
            !isTrivialMessage(it.content) && 
            it.content.trim() != newMessage.trim() 
        }.takeLast(config.maxUserTurns)

        if (priorUserMessages.isEmpty()) return false

        val priorTokens = priorUserMessages
            .flatMap { extractMeaningfulTokens(it.content) }
            .toSet()

        if (priorTokens.size < config.minMeaningfulWordCount) return false

        // Calculate overlap
        val intersection = newTokens.intersect(priorTokens)
        val union = newTokens.union(priorTokens)
        val overlapRatio = if (union.isEmpty()) 0.0 else intersection.size.toDouble() / union.size.toDouble()

        // Topic change if overlap is below threshold and the new message is substantive (>= 3 words)
        val wordCount = newMessage.trim().split(Regex("\\s+")).size
        return overlapRatio <= config.topicShiftOverlapThreshold && wordCount >= 3
    }

    /**
     * Builds a bounded context string from conversation history.
     */
    fun buildBoundedContext(
        history: List<Message>,
        newMessage: String? = null,
        isTopicChanged: Boolean = false
    ): Pair<String, List<Message>> {
        // Prepare current messages list
        val validMessages = history.filter { 
            it.content.isNotBlank() && 
            it.status != MessageStatus.ERROR 
        }.toMutableList()

        if (newMessage != null && newMessage.isNotBlank()) {
            if (validMessages.isEmpty() || validMessages.last().content != newMessage) {
                validMessages.add(
                    Message(
                        content = newMessage,
                        sender = Sender.USER,
                        timestamp = timeProvider.currentTimeMillis()
                    )
                )
            }
        }

        if (validMessages.isEmpty()) {
            return Pair("", emptyList())
        }

        // If topic change was detected, isolate messages from the latest user message forward
        val workingMessages = if (isTopicChanged) {
            val lastUserIdx = validMessages.indexOfLast { it.sender == Sender.USER && !isTrivialMessage(it.content) }
            if (lastUserIdx >= 0) {
                validMessages.subList(lastUserIdx, validMessages.size)
            } else {
                validMessages
            }
        } else {
            validMessages
        }

        // Take up to maxMessages
        val recentBounded = workingMessages.takeLast(config.maxMessages)

        // Build deterministic string: User: ... / Assistant: ...
        var currentLength = 0

        // Iterate backwards from newest to oldest to build within maxCharacters bound
        val formattedLines = mutableListOf<String>()
        for (msg in recentBounded.reversed()) {
            val prefix = if (msg.sender == Sender.USER) "User: " else "Assistant: "
            var line = "$prefix${msg.content.trim()}"
            
            if (line.length > config.maxCharacters) {
                line = line.take(config.maxCharacters)
            }
            
            if (currentLength + line.length + 1 > config.maxCharacters && formattedLines.isNotEmpty()) {
                // Character limit reached, stop adding older messages
                break
            }
            
            formattedLines.add(0, line) // Keep chronological order
            currentLength += line.length + 1
        }

        val boundedContextString = formattedLines.joinToString("\n")
        return Pair(boundedContextString, recentBounded)
    }

    /**
     * Main evaluation decision boundary entry point.
     */
    fun evaluateTrigger(
        event: ProactiveTriggerEvent,
        history: List<Message>,
        newMessage: String? = null,
        isIncognito: Boolean = false
    ): ProactiveEvaluationRequest {
        if (isIncognito) {
            return ProactiveEvaluationRequest(
                shouldEvaluate = false,
                eventType = event,
                reason = "Incognito mode is active; proactive evaluation disabled.",
                boundedContext = "",
                recentMessages = emptyList()
            )
        }

        val targetMessage = newMessage ?: history.lastOrNull { it.sender == Sender.USER }?.content ?: ""

        // 1. Trivial Message Suppression Check
        if (event == ProactiveTriggerEvent.MEANINGFUL_USER_MESSAGE || event == ProactiveTriggerEvent.TOPIC_CHANGED) {
            if (isTrivialMessage(targetMessage)) {
                return ProactiveEvaluationRequest(
                    shouldEvaluate = false,
                    eventType = event,
                    reason = "Trivial message suppressed from independent evaluation.",
                    boundedContext = "",
                    recentMessages = emptyList()
                )
            }
        }

        // 2. Detect Topic Change
        val isTopicShift = if (targetMessage.isNotBlank()) {
            detectTopicChange(history, targetMessage)
        } else false

        val effectiveEvent = if (isTopicShift) ProactiveTriggerEvent.TOPIC_CHANGED else event

        // 3. Build Bounded Context
        val (boundedContext, recentMessages) = buildBoundedContext(
            history = history,
            newMessage = newMessage,
            isTopicChanged = isTopicShift
        )

        if (boundedContext.isBlank()) {
            return ProactiveEvaluationRequest(
                shouldEvaluate = false,
                eventType = effectiveEvent,
                reason = "No context available for evaluation.",
                boundedContext = "",
                recentMessages = emptyList()
            )
        }

        // 4. Rapid Duplicate Evaluation Check
        val contextHash = boundedContext.hashCode()
        val currentTime = timeProvider.currentTimeMillis()
        if (contextHash == lastEvaluatedContextHash && (currentTime - lastEvaluationTimeMs) < config.rapidEvaluationCooldownMs) {
            return ProactiveEvaluationRequest(
                shouldEvaluate = false,
                eventType = effectiveEvent,
                reason = "Rapid duplicate context evaluation suppressed.",
                boundedContext = boundedContext,
                recentMessages = recentMessages,
                isTopicChanged = isTopicShift
            )
        }

        val reason = when (effectiveEvent) {
            ProactiveTriggerEvent.MEANINGFUL_USER_MESSAGE -> "Meaningful user message submitted."
            ProactiveTriggerEvent.ASSISTANT_RESPONSE_COMPLETED -> "Assistant response completed."
            ProactiveTriggerEvent.TOPIC_CHANGED -> "Topic change detected."
            ProactiveTriggerEvent.TURN_SEQUENCE_BOUNDARY -> "Turn sequence boundary reached."
            ProactiveTriggerEvent.EXPLICIT_EVALUATION -> "Explicit evaluation requested."
        }

        return ProactiveEvaluationRequest(
            shouldEvaluate = true,
            eventType = effectiveEvent,
            reason = reason,
            boundedContext = boundedContext,
            recentMessages = recentMessages,
            isTopicChanged = isTopicShift
        )
    }

    /**
     * Marks a context string as evaluated to prevent rapid duplication.
     */
    fun markEvaluated(boundedContext: String) {
        lastEvaluatedContextHash = boundedContext.hashCode()
        lastEvaluationTimeMs = timeProvider.currentTimeMillis()
    }

    /**
     * Resets tracking state (e.g. on new conversation or test reset).
     */
    fun resetState() {
        lastEvaluatedContextHash = null
        lastEvaluationTimeMs = 0L
    }
}
