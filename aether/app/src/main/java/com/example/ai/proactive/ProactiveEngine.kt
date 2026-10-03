package com.example.ai.proactive

import com.example.ai.TimeProvider
import com.example.ai.SystemTimeProvider
import com.example.data.model.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ProactiveEngine(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    // Thread-safe repository for cooldown tracking of surfaced memories: memoryId -> lastSurfacedTimeMillis
    private val surfacedSignals = ConcurrentHashMap<String, Long>()

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

        private val PROACTIVE_CATEGORIES = setOf(
            "goal", "goals", "project", "projects", "event", "events", "upcoming events", "temporary_event", 
            "unfinished_task", "task", "tasks", "repeated_concern", "future_intention", "important_decision"
        )
    }

    /**
     * Marks a signal (memory) as surfaced to start its cooldown window.
     */
    fun markAsSurfaced(memoryId: String) {
        surfacedSignals[memoryId] = timeProvider.currentTimeMillis()
    }

    /**
     * Clears cooldowns (useful for tests).
     */
    fun clearCooldowns() {
        surfacedSignals.clear()
    }

    /**
     * Decides whether to proactively surface a memory based on candidate signals and current context.
     * All retrieval and scoring are handled safely off the main thread.
     */
    suspend fun determineProactiveDecision(
        currentContext: String?,
        isIncognito: Boolean,
        memories: List<Memory>,
        preferences: ProactivePreferences = ProactivePreferences()
    ): ProactiveDecision = withContext(Dispatchers.Default) {
        
        // Incognito Safeguard
        if (isIncognito) {
            return@withContext ProactiveDecision(
                decision = ProactiveAction.STAY_SILENT,
                confidence = 0.0,
                reason = "Incognito mode is active; proactive intelligence is disabled.",
                category = "NONE"
            )
        }

        // Global User Control Check
        if (!preferences.isEnabled) {
            return@withContext ProactiveDecision(
                decision = ProactiveAction.STAY_SILENT,
                confidence = 0.0,
                reason = "Proactive intelligence is globally disabled by user preference.",
                category = "NONE"
            )
        }

        if (preferences.isQuietMode) {
            return@withContext ProactiveDecision(
                decision = ProactiveAction.STAY_SILENT,
                confidence = 0.0,
                reason = "Quiet mode is active; staying silent.",
                category = "NONE"
            )
        }

        if (memories.isEmpty()) {
            return@withContext ProactiveDecision(
                decision = ProactiveAction.STAY_SILENT,
                confidence = 0.0,
                reason = "Silence: no useful signal exists.",
                category = "NONE"
            )
        }

        val currentTime = timeProvider.currentTimeMillis()

        // 1. SIGNAL DETECTION & PRE-FILTERING (A)
        val candidateDecisions = memories.mapNotNull { memory ->
            // --- Hard Silence Rules (Privacy and Correctness overrides) ---
            
            // 1. Check if state/archival forbids it
            if (memory.isArchived || memory.state == "ARCHIVED") {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Candidate memory is archived.",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            if (memory.state == "SUPERSEDED") {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Candidate memory is superseded.",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // 2. Check Expiration
            if (memory.expirationTimestamp > 0L && currentTime >= memory.expirationTimestamp) {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Candidate temporary event is expired.",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // 3. Confidence check (must be CONFIRMED or high score)
            if (memory.confidenceScore < 3) {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Candidate memory has low confidence score (${memory.confidenceScore}).",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // 4. Stale Information Check
            val ageMs = currentTime - memory.lastUpdatedTimestamp
            val ageDays = ageMs.toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
            if (ageDays > 30.0 || memory.state == "STALE") {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Candidate memory is stale (age: ${ageDays.toInt()} days).",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // 5. Cooldown check
            val lastSurfaced = surfacedSignals[memory.id] ?: 0L
            if (currentTime - lastSurfaced < preferences.cooldownDurationMs) {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Signal already surfaced within cooldown window.",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // 6. Generic preference classification
            val categoryLower = memory.category.lowercase().trim()
            val isProactiveCategory = categoryLower in PROACTIVE_CATEGORIES || 
                    PROACTIVE_CATEGORIES.any { categoryLower.contains(it) }

            if (!isProactiveCategory) {
                // Check if memory represents a goal, task or project by contents anyway
                val isGoalByContent = memory.content.lowercase().contains("goal") ||
                        memory.content.lowercase().contains("project") ||
                        memory.content.lowercase().contains("plan") ||
                        memory.content.lowercase().contains("todo")
                
                if (!isGoalByContent) {
                    return@mapNotNull ProactiveDecision(
                        decision = ProactiveAction.STAY_SILENT,
                        confidence = 0.0,
                        reason = "Generic preference or memory type does not qualify as proactive signal.",
                        category = "NONE",
                        supportingMemory = memory
                    )
                }
            }

            // --- 2. CONFIDENCE & RELEVANCE SCORING (B) ---
            var relevanceScore = 0.0
            
            if (!currentContext.isNullOrBlank()) {
                val contextTokens = currentContext.lowercase()
                    .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                    .split("\\s+".toRegex())
                    .filter { it.length > 2 && it !in STOPWORDS }
                    .toSet()

                val memoryTokens = memory.content.lowercase()
                    .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                    .split("\\s+".toRegex())
                    .filter { it.length > 2 && it !in STOPWORDS }
                    .toSet()

                if (contextTokens.isNotEmpty() && memoryTokens.isNotEmpty()) {
                    val overlap = contextTokens.intersect(memoryTokens).size
                    val baseDenom = minOf(contextTokens.size, memoryTokens.size)
                    relevanceScore = if (baseDenom == 0) 0.0 else overlap.toDouble() / baseDenom
                    
                    // Boost if there is an exact sequential phrase match
                    if (hasPhraseMatch(currentContext, memory.content)) {
                        relevanceScore += 0.5
                    }
                }
            } else {
                // If there's no context, relevance is 0.0
                relevanceScore = 0.0
            }

            // We require a minimum relevance threshold if context is active to prevent false alarms
            if (!currentContext.isNullOrBlank() && relevanceScore < 0.1) {
                return@mapNotNull ProactiveDecision(
                    decision = ProactiveAction.STAY_SILENT,
                    confidence = 0.0,
                    reason = "Weak keyword relevance ($relevanceScore) to current conversation context.",
                    category = "NONE",
                    supportingMemory = memory
                )
            }

            // Confidence normalization (max score is 5)
            val confidenceNorm = memory.confidenceScore.toDouble() / 5.0

            // Priority score based on category
            val categoryPriority = when {
                categoryLower.contains("goal") -> 1.0
                categoryLower.contains("project") -> 1.0
                categoryLower.contains("event") -> 0.9
                categoryLower.contains("task") -> 0.8
                else -> 0.6
            }

            // Urgency score
            var urgencyScore = 0.0
            if (memory.temporalType == "TEMPORARY_EVENT") {
                val remainingTimeMs = memory.expirationTimestamp - currentTime
                if (remainingTimeMs > 0L) {
                    val ageMsTotal = memory.expirationTimestamp - memory.createdTimestamp
                    urgencyScore = if (ageMsTotal > 0) {
                        1.0 - (remainingTimeMs.toDouble() / ageMsTotal.toDouble())
                    } else {
                        0.5
                    }
                }
            }

            // Weighted combination formula
            val finalScore = (relevanceScore * 0.4) + (confidenceNorm * 0.3) + (categoryPriority * 0.2) + (urgencyScore * 0.1)

            val action = if (finalScore >= preferences.sensitivityThreshold) {
                ProactiveAction.PROACT
            } else {
                ProactiveAction.STAY_SILENT
            }

            val reason = if (action == ProactiveAction.PROACT) {
                "High relevance proactive signal detected of category '${memory.category}' with confidence ${String.format("%.2f", finalScore)}."
            } else {
                "Signal score (${String.format("%.2f", finalScore)}) is below threshold (${preferences.sensitivityThreshold})."
            }

            ProactiveDecision(
                decision = action,
                confidence = finalScore,
                reason = reason,
                category = memory.category,
                supportingMemory = memory,
                priority = when {
                    finalScore >= 0.8 -> 3
                    finalScore >= 0.6 -> 2
                    else -> 1
                }
            )
        }

        // Find the best PROACT candidate or the closest silent reasoning
        val proactCandidates = candidateDecisions.filter { it.decision == ProactiveAction.PROACT }
        
        if (proactCandidates.isNotEmpty()) {
            proactCandidates.maxByOrNull { it.confidence }!!
        } else {
            // Find the best Silent decision to explain why we remained quiet
            val fallback = candidateDecisions.maxByOrNull { it.confidence }
            fallback ?: ProactiveDecision(
                decision = ProactiveAction.STAY_SILENT,
                confidence = 0.0,
                reason = "Silence: no useful signal exists.",
                category = "NONE"
            )
        }
    }

    /**
     * Minimal clean adapter that allows evaluating detected ProactiveSignals using the existing ProactiveEngine.
     */
    suspend fun determineDecisionForSignals(
        currentContext: String?,
        isIncognito: Boolean,
        signals: List<ProactiveSignal>,
        preferences: ProactivePreferences = ProactivePreferences()
    ): ProactiveDecision {
        val memories = signals.map { signal ->
            Memory(
                id = signal.sourceMemoryId ?: signal.id,
                content = signal.title.substringAfter(": ").trim(),
                category = when (signal.type) {
                    ProactiveSignalType.GOAL_RELEVANT -> "Goals"
                    ProactiveSignalType.PROJECT_RELEVANT -> "Projects"
                    ProactiveSignalType.UPCOMING_EVENT -> "Events"
                    ProactiveSignalType.UNFINISHED_ITEM -> "Tasks"
                    ProactiveSignalType.REPEATED_CONCERN -> "Concern"
                    ProactiveSignalType.FUTURE_INTENTION -> "Intention"
                    ProactiveSignalType.IMPORTANT_DECISION -> "Decision"
                },
                createdTimestamp = signal.timestamp,
                lastUpdatedTimestamp = signal.timestamp,
                sourceConversationId = null,
                isArchived = false,
                confidenceScore = if (signal.priority == 3) 5 else if (signal.priority == 2) 3 else 1,
                state = "CONFIRMED",
                temporalType = if (signal.type == ProactiveSignalType.UPCOMING_EVENT) "TEMPORARY_EVENT" else "PERSISTENT",
                expirationTimestamp = signal.expirationTimestamp ?: 0L
            )
        }
        return determineProactiveDecision(currentContext, isIncognito, memories, preferences)
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
