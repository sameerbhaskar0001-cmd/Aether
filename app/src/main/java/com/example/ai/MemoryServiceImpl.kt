package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.Part
import com.example.ai.network.RetrofitClient
import com.example.data.model.Memory
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.Exception
import java.lang.StringBuilder

object MemoryConstants {
    const val CONFIRMED_THRESHOLD = 3
    const val STALE_THRESHOLD_DAYS = 30

    const val BASE_RELEVANCE_MATCH = 1.0f
    const val PHRASE_MATCH_BOOST = 1.5f
    const val CATEGORY_RELEVANCE_BOOST = 1.0f
    const val CONFIDENCE_WEIGHT_BOOST = 0.5f
    const val RECENCY_BOOST_MAX = 0.5f
    const val STALE_PENALTY = 1.5f
}

class MemoryServiceImpl(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) : MemoryService {

    companion object {
        private const val TAG = "MemoryServiceImpl"
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
        private val SYNONYMS = mapOf(
            "coding" to setOf("programming", "developer", "software", "code", "java", "kotlin"),
            "programming" to setOf("coding", "developer", "software", "code", "java", "kotlin"),
            "app" to setOf("mobile", "android", "ios", "phone", "application"),
            "mobile" to setOf("app", "android", "ios", "phone", "application"),
            "job" to setOf("work", "career", "employment", "company", "position"),
            "work" to setOf("job", "career", "employment", "company", "position")
        )
    }

    override suspend fun getRelevantMemories(query: String, allMemories: List<Memory>): List<Memory> = withContext(Dispatchers.IO) {
        val queryTokens = query.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

        if (queryTokens.isEmpty()) return@withContext emptyList()

        // Local Synonym Expansion
        val expandedQueryTokens = queryTokens.toMutableSet()
        queryTokens.forEach { token ->
            SYNONYMS[token]?.let { syns ->
                expandedQueryTokens.addAll(syns)
            }
        }

        allMemories.mapNotNull { memory ->
            // Exclude ARCHIVED and SUPERSEDED completely
            if (memory.isArchived || memory.state == "ARCHIVED" || memory.state == "SUPERSEDED") {
                return@mapNotNull null
            }

            // Exclude EXPIRED memories
            if (memory.expirationTimestamp > 0L && timeProvider.currentTimeMillis() >= memory.expirationTimestamp) {
                return@mapNotNull null
            }

            val memoryTokens = memory.content.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .split("\\s+".toRegex())
                .filter { it.length > 2 && it !in STOPWORDS }
                .toSet()

            val categoryTokens = memory.category.lowercase()
                .replace(Regex("[^a-zA-Z0-9\\s]"), "")
                .split("\\s+".toRegex())
                .toSet()

            // 1. Keyword Overlap Relevance
            val overlapCount = expandedQueryTokens.intersect(memoryTokens).size
            val relevanceScore = if (expandedQueryTokens.isNotEmpty()) {
                (overlapCount.toFloat() / expandedQueryTokens.size) * MemoryConstants.BASE_RELEVANCE_MATCH
            } else {
                0.0f
            }

            // 2. Sequential Phrase Matching
            val hasPhraseMatch = hasPhraseMatch(query, memory.content)
            val phraseBoost = if (hasPhraseMatch) MemoryConstants.PHRASE_MATCH_BOOST else 0.0f

            // 3. Category Match Boost
            val categoryBoost = if (queryTokens.intersect(categoryTokens).isNotEmpty()) {
                MemoryConstants.CATEGORY_RELEVANCE_BOOST
            } else {
                0.0f
            }

            // 4. Confidence Weight Boost
            val isConfirmed = memory.state == "CONFIRMED" || memory.confidenceScore >= MemoryConstants.CONFIRMED_THRESHOLD
            val confidenceBoost = if (isConfirmed) MemoryConstants.CONFIDENCE_WEIGHT_BOOST else 0.0f

            // 5. Recency Boost
            val daysSinceUpdate = (timeProvider.currentTimeMillis() - memory.lastUpdatedTimestamp).toFloat() / (1000f * 60 * 60 * 24)
            val recencyBoost = if (daysSinceUpdate < MemoryConstants.STALE_THRESHOLD_DAYS) {
                MemoryConstants.RECENCY_BOOST_MAX * (1.0f - (daysSinceUpdate / MemoryConstants.STALE_THRESHOLD_DAYS))
            } else {
                0.0f
            }.coerceAtLeast(0.0f)

            // 6. Stale Penalty
            val isStale = memory.state == "STALE" || daysSinceUpdate > MemoryConstants.STALE_THRESHOLD_DAYS
            val stalePenalty = if (isStale) MemoryConstants.STALE_PENALTY else 0.0f

            val finalScore = relevanceScore + phraseBoost + categoryBoost + confidenceBoost + recencyBoost - stalePenalty

            if (finalScore > 0.0f) {
                Pair(memory, finalScore)
            } else {
                null
            }
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(5)
    }

    private fun hasPhraseMatch(query: String, content: String): Boolean {
        val cleanQuery = query.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        val cleanContent = content.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), "").trim()
        val queryWords = cleanQuery.split("\\s+".toRegex()).filter { it.length > 2 && it !in STOPWORDS }
        
        if (queryWords.size >= 2) {
            for (i in 0..queryWords.size - 2) {
                val phrase2 = "${queryWords[i]} ${queryWords[i+1]}"
                if (cleanContent.contains(phrase2)) {
                    return true
                }
            }
        }
        return false
    }

    override suspend fun extractMemoryDeltas(
        conversationId: String,
        messages: List<Message>,
        existingMemories: List<Memory>
    ): List<MemoryDelta> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is not configured for memory extraction.")
            return@withContext emptyList()
        }

        // Keep last 10 messages for context so memory extraction is focused and fast, filtering out protected payloads
        val conversationHistorySegment = messages
            .filter { msg ->
                val content = msg.content.trim()
                val isProtected = content.startsWith("Don't remember this:", ignoreCase = true) ||
                                  content.startsWith("Do not remember this:", ignoreCase = true)
                msg.status == MessageStatus.SENT && content.isNotBlank() && !isProtected
            }
            .takeLast(10)

        if (conversationHistorySegment.isEmpty()) return@withContext emptyList()

        // Build conversation transcript text
        val transcript = StringBuilder()
        conversationHistorySegment.forEach { msg ->
            val senderLabel = if (msg.sender == com.example.data.model.Sender.USER) "User" else "Aether"
            transcript.append("$senderLabel: ${msg.content}\n")
        }

        // Convert existing memories to a lightweight JSON for context
        val memoriesJsonList = existingMemories.map {
            mapOf(
                "id" to it.id,
                "content" to it.content,
                "category" to it.category,
                "temporalType" to it.temporalType,
                "expirationTimestamp" to it.expirationTimestamp
            )
        }
        val memoriesJsonAdapter = RetrofitClient.moshi.adapter(List::class.java)
        val existingMemoriesJsonStr = memoriesJsonAdapter.toJson(memoriesJsonList)

        // Build temporal reference context
        val zoneId = java.time.ZoneId.of(timeProvider.getZoneId())
        val zonedDateTime = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(timeProvider.currentTimeMillis()), zoneId)
        val formattedDate = zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val formattedTime = zonedDateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val dayOfWeek = zonedDateTime.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val timezoneStr = timeProvider.getZoneId()

        val timeContext = """
            CurrentDate: $formattedDate
            CurrentTime: $formattedTime
            Timezone: $timezoneStr
            CurrentDayOfWeek: $dayOfWeek
        """.trimIndent()

        // Refined prompt for memory extraction with strict Fact Verification and Temporal Intelligence rules
        val prompt = """
            You are the background long-term memory extraction system of Aether, an AI assistant.
            Your job is to analyze a conversation transcript between Aether and the User, compare it with the User's current list of existing memories, and extract structured memory updates, additions, or archiving actions.
            
            --- CURRENT SYSTEM DATE/TIME REFERENCE ---
            $timeContext
            
            --- RULES ---
            1. ONLY extract information that is genuinely durable and useful for future conversations (such as: stable preferences, long-term goals, ongoing projects, important decisions, stable user facts, scheduled events).
            2. Do NOT extract:
               - Hypothetical examples ("Suppose I learn Kotlin someday", "If I were to buy a car")
               - Research / information queries ("How do I fix this gradle bug?", "What is TF-IDF?")
               - Temporary problems ("My server is down right now")
               - Assistant-generated assumptions or random conversational filler ("Ok great", "Perfect thanks!")
               - Statements about other people unless directly relevant to the user.
            3. If the user mentions new information that updates or contradicts an existing memory (e.g., changing preference, finishing/stopping a project, transitioning tech stack, rescheduled event), output an UPDATE action pointing to the existing memory's ID.
            4. If a goal or project is completed or no longer relevant, you can output an ARCHIVE action pointing to the existing memory's ID.
            5. Category options MUST be one of: "About Me", "Goals", "Projects", "Ideas", "Preferences", "Learning", "Skills", "Decisions", "Important Events", "Other".
            6. TEMPORAL INTELLIGENCE (CRITICAL):
               - Determine if the memory is "PERSISTENT" (long-term preferences, facts) or a "TEMPORARY_EVENT" (interviews, exams, trips, meetings, workshops that occur at a specific date/time).
               - When the memory contains relative-time words (e.g., "today", "tomorrow", "yesterday", "tonight", "this weekend", "next week", "next month", "in two days", "last week", "this Friday", "next Monday"), you MUST resolve them to an absolute calendar date (format: "YYYY-MM-DD" or "YYYY-MM-DD HH:mm" if a specific hour/time is supplied) using the CURRENT SYSTEM DATE/TIME REFERENCE provided.
               - In the "content" field of your output, you MUST replace the relative word with the absolute resolved calendar representation. Under no circumstances write relative words like "tomorrow" or "next week" literally in the "content" field. E.g., change "I have an interview tomorrow" to "User has an interview on 2026-09-25."
               - NO FALSE PRECISION: Do NOT invent a time (do not assume 09:00, etc.) if the user only supplied a date. Keep it as "YYYY-MM-DD".
               - For TEMPORARY_EVENT, set "temporalType" to "TEMPORARY_EVENT" and provide the "expirationDate" in "YYYY-MM-DD" or "YYYY-MM-DD HH:mm" format.
               - For PERSISTENT memories, set "temporalType" to "PERSISTENT" and "expirationDate" to null.
            
            --- CURRENT EXISTING MEMORIES ---
            $existingMemoriesJsonStr
            
            --- RECENT CONVERSATION ---
            $transcript
            
            --- OUTPUT FORMAT ---
            You MUST respond with a single, raw, valid JSON array of actions. Do NOT write any chat wrapper, conversational text, introduction, or explanations. Do NOT use markdown blocks like ```json.
            Each element in the array MUST be a JSON object matching this structure:
            [
              {
                "action": "ADD",
                "category": "CategoryName",
                "content": "Specific memory content written in third person, e.g., 'User is learning Android development.' or 'User has an interview on 2026-09-25.'",
                "temporalType": "PERSISTENT | TEMPORARY_EVENT",
                "expirationDate": "YYYY-MM-DD | YYYY-MM-DD HH:mm | null"
              },
              {
                "action": "UPDATE",
                "existingMemoryId": "unique-id-from-existing-memories",
                "category": "CategoryName",
                "content": "Updated memory description in third person.",
                "temporalType": "PERSISTENT | TEMPORARY_EVENT",
                "expirationDate": "YYYY-MM-DD | YYYY-MM-DD HH:mm | null"
              },
              {
                "action": "ARCHIVE",
                "existingMemoryId": "unique-id-from-existing-memories"
              }
            ]
            If no new memories or changes are needed, return an empty array: []
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = prompt)))
            )
        )

        try {
            val response = retryWithBackoff {
                RetrofitClient.service.generateContent(apiKey, request)
            }
            val rawResponseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
            val cleanJsonText = cleanJson(rawResponseText)
            
            if (cleanJsonText.isBlank() || cleanJsonText == "[]") {
                return@withContext emptyList()
            }

            val listType = Types.newParameterizedType(List::class.java, Map::class.java)
            val adapter = RetrofitClient.moshi.adapter<List<Map<String, Any>>>(listType)
            val rawDeltas = adapter.fromJson(cleanJsonText) ?: emptyList()

            return@withContext rawDeltas.mapNotNull { map ->
                val action = map["action"] as? String ?: return@mapNotNull null
                val category = map["category"] as? String
                val content = map["content"] as? String
                val existingMemoryId = map["existingMemoryId"] as? String
                
                val temporalType = map["temporalType"] as? String ?: "PERSISTENT"
                val expirationDateStr = map["expirationDate"] as? String
                val expirationTimestamp = parseExpirationToMillis(expirationDateStr, timeProvider.getZoneId(), timeProvider)

                MemoryDelta(
                    action = action,
                    category = category,
                    content = content,
                    existingMemoryId = existingMemoryId,
                    temporalType = temporalType,
                    expirationTimestamp = expirationTimestamp
                )
            }
        } catch (e: Exception) {
            val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
            Log.e(TAG, "Failed to extract memories: $safeErr")
            return@withContext emptyList()
        }
    }

    internal fun parseExpirationToMillis(expirationStr: String?, timezoneStr: String, timeProvider: TimeProvider): Long {
        if (expirationStr.isNullOrBlank()) return 0L
        val zoneId = try {
            java.time.ZoneId.of(timezoneStr)
        } catch (e: Exception) {
            java.time.ZoneId.of(timeProvider.getZoneId())
        }
        
        return try {
            if (expirationStr.contains(":") || expirationStr.contains(" ") || expirationStr.contains("T")) {
                // e.g. "2026-09-25 15:00" or "2026-09-25T15:00"
                val cleanStr = expirationStr.replace("T", " ").trim()
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                val localDateTime = java.time.LocalDateTime.parse(cleanStr, formatter)
                val zonedDateTime = java.time.ZonedDateTime.of(localDateTime, zoneId)
                zonedDateTime.toInstant().toEpochMilli()
            } else {
                // e.g. "2026-09-25"
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                val localDate = java.time.LocalDate.parse(expirationStr.trim(), formatter)
                // Expiry is at the end of that day (23:59:59)
                val localDateTime = localDate.atTime(23, 59, 59)
                val zonedDateTime = java.time.ZonedDateTime.of(localDateTime, zoneId)
                zonedDateTime.toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse expiration date: $expirationStr", e)
            0L
        }
    }

    private suspend fun <T> retryWithBackoff(
        times: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 6000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                val code = (e as? retrofit2.HttpException)?.code()
                if (code == 503 || code == 429 || e is java.io.IOException) {
                    val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
                    Log.w(TAG, "Retryable network error (code $code, attempt ${attempt + 1}/$times): $safeErr. Retrying in ${currentDelay}ms...")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                } else {
                    throw e
                }
            }
        }
        return block()
    }

    private fun cleanJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.substringAfter("```")
            if (text.startsWith("json", ignoreCase = true)) {
                text = text.substring(4)
            }
            text = text.substringBeforeLast("```")
        }
        return text.trim()
    }
}
