package com.example.ai.proactive

import com.example.ai.TimeProvider
import com.example.ai.SystemTimeProvider
import com.example.data.model.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProactiveSignalDetector(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    companion object {
        private val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "is", "are", "was", "were", 
            "be", "been", "being", "to", "from", "in", "on", "at", "by", "for", "with", "about", 
            "against", "between", "into", "through", "during", "before", "after", "above", "below", 
            "up", "down", "off", "over", "under", "again", "further", "once", "here", "there", 
            "when", "where", "why", "how", "all", "any", "both", "each", "few", "more", "most", 
            "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", 
            "too", "very", "can", "will", "just", "should", "now", "i", "my", "me", "we", "our", 
            "us", "you", "your", "he", "she", "it", "they", "them"
        )

        private val HYPOTHETICAL_TRIGGERS = setOf(
            "would like to", "might", "maybe", "if i ever", "could possibly", 
            "hypothetically", "imagine if", "wish i could", "perhaps", "dream of", "someday"
        )

        private val ASSUMPTION_TRIGGERS = setOf(
            "assistant assumes", "automatically assumed", "assumed that", "assumption"
        )

        private val UNFINISHED_TRIGGERS = setOf(
            "todo", "to-do", "pending", "unfinished", "incomplete", "not completed", 
            "not finished", "still working on", "in progress", "remaining", "not yet"
        )

        private val FUTURE_INTENTION_TRIGGERS = setOf(
            "intend to", "will do", "plan to", "going to", "scheduled to", "decided to", "resolved to"
        )
        
        private val VAGUE_ASPIRATIONS = setOf(
            "wish i could", "hope to", "maybe someday", "would be nice to", "dream of", "someday"
        )

        private val CONCERN_TRIGGERS = setOf(
            "concern", "worried", "worries", "issue", "trouble", "afraid", "problem", "broken", "leak"
        )
    }

    /**
     * Scans memories and returns a list of detected proactive signals in deterministic order.
     */
    suspend fun detectSignals(
        currentContext: String?,
        isIncognito: Boolean,
        memories: List<Memory>
    ): List<ProactiveSignal> = withContext(Dispatchers.Default) {
        if (isIncognito) {
            return@withContext emptyList()
        }

        val currentTime = timeProvider.currentTimeMillis()
        val signals = mutableListOf<ProactiveSignal>()

        for (memory in memories) {
            // --- Hard Safety/Quality Filters ---
            
            // 1. Archived/Superseded/Stale Check
            if (memory.isArchived || memory.state == "ARCHIVED" || memory.state == "SUPERSEDED" || memory.state == "STALE") {
                continue
            }

            // 1b. Check Stale Age (>30 days for non-temporary events)
            val ageMs = currentTime - memory.lastUpdatedTimestamp
            val ageDays = ageMs.toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
            if (ageDays > 30.0 && memory.temporalType != "TEMPORARY_EVENT") {
                continue
            }

            // 1c. Contradiction / Newer Memory Superseding Check
            val isSupersededByNewer = memories.any { newer ->
                newer.id != memory.id &&
                !newer.isArchived &&
                newer.state != "ARCHIVED" &&
                newer.state != "SUPERSEDED" &&
                newer.confidenceScore >= 3 &&
                (newer.createdTimestamp > memory.createdTimestamp || newer.lastUpdatedTimestamp > memory.lastUpdatedTimestamp) &&
                calculateOverlapCoefficient(memory.content, newer.content) > 0.35
            }
            if (isSupersededByNewer) {
                continue
            }

            // 2. Low-confidence Check
            if (memory.confidenceScore < 3 || memory.state == "CANDIDATE") {
                continue
            }

            // 3. Expired Event Check
            if (memory.expirationTimestamp > 0L && currentTime >= memory.expirationTimestamp) {
                continue
            }

            // 4. Hypothetical Statement Check
            val contentLower = memory.content.lowercase()
            if (HYPOTHETICAL_TRIGGERS.any { contentLower.contains(it) }) {
                continue
            }

            // 5. Assistant Assumption Check
            val categoryLower = memory.category.lowercase()
            if (categoryLower.contains("assumption") || ASSUMPTION_TRIGGERS.any { contentLower.contains(it) }) {
                continue
            }

            // 6. Temporary/Filler Check
            if (memory.temporalType == "TEMPORARY" || isFillerContent(contentLower)) {
                continue
            }

            // Calculate context relevance score
            val relevanceScore = calculateRelevance(memory.content, currentContext)

            // 7. Generic Preference Check: Filter out if category is Preferences and relevance is low
            val isPreference = categoryLower.contains("preference") || contentLower.contains("prefer") || contentLower.contains("like")
            if (isPreference && relevanceScore < 0.3) {
                continue
            }

            // 8. General relevance gate for context-sensitive signals
            // (Time-sensitive UPCOMING_EVENT doesn't strictly need high context relevance if it's very close in time, 
            // but for everything else, we require a meaningful relevance score when currentContext is provided)
            val hasContext = !currentContext.isNullOrBlank()
            if (hasContext && relevanceScore < 0.2 && memory.temporalType != "TEMPORARY_EVENT") {
                continue
            }

            // --- Signal Categorization ---

            // A. GOAL_RELEVANT
            val isGoal = categoryLower.contains("goal") || categoryLower.contains("plan")
            if (isGoal && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_GOAL_RELEVANT",
                        type = ProactiveSignalType.GOAL_RELEVANT,
                        title = "Goal: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = if (memory.confidenceScore >= 5) 3 else 2,
                        timestamp = currentTime,
                        explanation = "Confirmed goal is strongly relevant to the current conversation."
                    )
                )
            }

            // B. PROJECT_RELEVANT
            val isProject = categoryLower.contains("project") || categoryLower.contains("work")
            if (isProject && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_PROJECT_RELEVANT",
                        type = ProactiveSignalType.PROJECT_RELEVANT,
                        title = "Project: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = if (memory.confidenceScore >= 5) 3 else 2,
                        timestamp = currentTime,
                        explanation = "Confirmed project is strongly relevant to the current conversation."
                    )
                )
            }

            // C. UPCOMING_EVENT
            val isUpcomingEvent = memory.temporalType == "TEMPORARY_EVENT" || categoryLower.contains("event")
            if (isUpcomingEvent) {
                val isExpired = memory.expirationTimestamp > 0L && currentTime >= memory.expirationTimestamp
                if (!isExpired) {
                    val remainingMs = memory.expirationTimestamp - currentTime
                    // If event is in the future and less than 7 days away (or no expiration but explicitly marked)
                    val isApproaching = memory.expirationTimestamp == 0L || (remainingMs in 0L..(7L * 24 * 60 * 60 * 1000))
                    if (isApproaching) {
                        signals.add(
                            ProactiveSignal(
                                id = "sig_${memory.id}_UPCOMING_EVENT",
                                type = ProactiveSignalType.UPCOMING_EVENT,
                                title = "Upcoming Event: ${truncateTitle(memory.content)}",
                                sourceMemoryId = memory.id,
                                relevanceContext = currentContext,
                                confidence = if (hasContext) relevanceScore else 1.0,
                                priority = 3, // Events are highly prioritized
                                timestamp = currentTime,
                                expirationTimestamp = if (memory.expirationTimestamp > 0L) memory.expirationTimestamp else null,
                                isTimeSensitive = true,
                                explanation = "Valid upcoming temporary event is approaching its event time."
                            )
                        )
                    }
                }
            }

            // D. UNFINISHED_ITEM
            val hasUnfinishedKeyword = UNFINISHED_TRIGGERS.any { contentLower.contains(it) }
            val isUnfinishedType = categoryLower.contains("task") || categoryLower.contains("todo")
            if ((hasUnfinishedKeyword || isUnfinishedType) && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_UNFINISHED_ITEM",
                        type = ProactiveSignalType.UNFINISHED_ITEM,
                        title = "Unfinished Task: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = 2,
                        timestamp = currentTime,
                        isUnfinished = true,
                        explanation = "Unfinished item is explicitly supported by existing memory."
                    )
                )
            }

            // E. REPEATED_CONCERN
            // Check if this memory qualifies as a concern or worry
            val isConcernKeyword = CONCERN_TRIGGERS.any { contentLower.contains(it) }
            val hasOverlappingEvidence = isConcernKeyword && memories.any { other ->
                other.id != memory.id && 
                !other.isArchived && 
                other.state != "ARCHIVED" && 
                other.state != "SUPERSEDED" &&
                calculateOverlapCoefficient(memory.content, other.content) > 0.4
            }
            if (hasOverlappingEvidence && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_REPEATED_CONCERN",
                        type = ProactiveSignalType.REPEATED_CONCERN,
                        title = "Concern: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = 3,
                        timestamp = currentTime,
                        explanation = "Repeated topic with sufficient evidence is highly relevant to current context."
                    )
                )
            }

            // F. FUTURE_INTENTION
            val isFutureIntention = FUTURE_INTENTION_TRIGGERS.any { contentLower.contains(it) } && 
                    !VAGUE_ASPIRATIONS.any { contentLower.contains(it) }
            if (isFutureIntention && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_FUTURE_INTENTION",
                        type = ProactiveSignalType.FUTURE_INTENTION,
                        title = "Intention: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = 2,
                        timestamp = currentTime,
                        explanation = "Explicit future intention becomes relevant to the current conversation."
                    )
                )
            }

            // G. IMPORTANT_DECISION
            val isDecision = categoryLower.contains("decision") || contentLower.contains("decided") || contentLower.contains("agreed to")
            if (isDecision && relevanceScore >= 0.2) {
                signals.add(
                    ProactiveSignal(
                        id = "sig_${memory.id}_IMPORTANT_DECISION",
                        type = ProactiveSignalType.IMPORTANT_DECISION,
                        title = "Decision: ${truncateTitle(memory.content)}",
                        sourceMemoryId = memory.id,
                        relevanceContext = currentContext,
                        confidence = relevanceScore,
                        priority = 2,
                        timestamp = currentTime,
                        explanation = "Confirmed important decision is relevant to current context."
                    )
                )
            }
        }

        // Deduplication and ordering
        val deduplicated = signals.distinctBy { it.id }

        // Ordering by criteria:
        // 1. Time-sensitive
        // 2. Priority/Importance (higher first)
        // 3. Relevance confidence (higher first)
        // 4. Stable tie-breaker (ID alphabetical)
        deduplicated.sortedWith(
            compareByDescending<ProactiveSignal> { it.isTimeSensitive }
                .thenByDescending { it.priority }
                .thenByDescending { it.confidence }
                .thenBy { it.id }
        )
    }

    private fun isFillerContent(content: String): Boolean {
        val trimmed = content.trim().replace(Regex("[^a-zA-Z\\s]"), "")
        val fillerWords = setOf("uh", "hey", "hello", "test", "ok", "okay", "hi", "bye", "hmm", "ah")
        return trimmed in fillerWords || trimmed.length <= 2
    }

    private fun truncateTitle(content: String): String {
        return if (content.length > 50) "${content.take(47)}..." else content
    }

    private fun calculateRelevance(content: String, currentContext: String?): Double {
        if (currentContext.isNullOrBlank()) return 0.0
        val contextTokens = getCleanTokens(currentContext)
        if (contextTokens.isEmpty()) return 0.0

        val contentTokens = getCleanTokens(content)
        if (contentTokens.isEmpty()) return 0.0

        val overlap = contextTokens.intersect(contentTokens).size
        val baseDenom = minOf(contextTokens.size, contentTokens.size)
        var score = if (baseDenom == 0) 0.0 else overlap.toDouble() / baseDenom

        if (hasPhraseMatch(currentContext, content)) {
            score += 0.5
        }
        return score
    }

    private fun getCleanTokens(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()
    }

    private fun calculateOverlapCoefficient(text1: String, text2: String): Double {
        val tokens1 = getCleanTokens(text1)
        val tokens2 = getCleanTokens(text2)
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
        val intersectionSize = tokens1.intersect(tokens2).size
        return intersectionSize.toDouble() / minOf(tokens1.size, tokens2.size)
    }

    private fun hasPhraseMatch(query: String, content: String): Boolean {
        val cleanQuery = query.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        val cleanContent = content.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        val queryWords = cleanQuery.split("\\s+".toRegex()).filter { it.length > 2 && it !in STOPWORDS }

        if (queryWords.size >= 2) {
            for (i in 0..queryWords.size - 2) {
                val phrase = "${queryWords[i]} ${queryWords[i+1]}"
                if (cleanContent.contains(phrase)) {
                    return true
                }
            }
        }
        return false
    }
}
