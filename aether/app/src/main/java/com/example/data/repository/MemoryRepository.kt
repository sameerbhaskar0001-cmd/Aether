package com.example.data.repository

import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.MemoryEntity
import com.example.data.model.Memory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface MemoryRepository {
    fun getAllMemoriesFlow(): Flow<List<Memory>>
    suspend fun getAllActiveMemories(): List<Memory>
    suspend fun getMemoryById(id: String): Memory?
    suspend fun getMemoriesByCategory(category: String): List<Memory>
    suspend fun saveMemory(memory: Memory)
    suspend fun deleteMemory(memory: Memory)
    suspend fun deleteMemoryById(id: String)
    suspend fun searchMemories(query: String): List<Memory>
}

class MemoryRepositoryImpl(
    private val memoryDao: MemoryDao,
    private val timeProvider: com.example.ai.TimeProvider = com.example.ai.SystemTimeProvider()
) : MemoryRepository {

    override fun getAllMemoriesFlow(): Flow<List<Memory>> {
        return memoryDao.getAllMemoriesFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAllActiveMemories(): List<Memory> {
        return memoryDao.getAllActiveMemoriesOptimized(timeProvider.currentTimeMillis())
            .map { it.toDomain() }
    }

    override suspend fun getMemoryById(id: String): Memory? {
        return memoryDao.getMemoryById(id)?.toDomain()
    }

    override suspend fun getMemoriesByCategory(category: String): List<Memory> {
        return memoryDao.getMemoriesByCategory(category).map { it.toDomain() }
    }

    override suspend fun saveMemory(memory: Memory) {
        val existingById = memoryDao.getMemoryById(memory.id)
        if (existingById != null) {
            // Saving/updating an existing memory ID (e.g., archiving or setting state manually)
            memoryDao.insertMemory(
                MemoryEntity(
                    id = memory.id,
                    content = memory.content,
                    category = memory.category,
                    createdTimestamp = memory.createdTimestamp,
                    lastUpdatedTimestamp = memory.lastUpdatedTimestamp,
                    sourceConversationId = memory.sourceConversationId,
                    isArchived = memory.isArchived,
                    confidenceScore = memory.confidenceScore,
                    lastAccessedTimestamp = memory.lastAccessedTimestamp,
                    accessCount = memory.accessCount,
                    state = memory.state,
                    temporalType = memory.temporalType,
                    expirationTimestamp = memory.expirationTimestamp
                )
            )
            return
        }

        // Search active memories for sufficiently similar matches
        val activeEntities = memoryDao.getAllActiveMemories().filter { entity ->
            entity.state != "SUPERSEDED" && entity.state != "ARCHIVED" && !entity.isArchived
        }

        val similarEntity = activeEntities.firstOrNull { entity ->
            areMemoriesSimilar(entity.content, memory.content)
        }

        if (similarEntity != null) {
            // Deduplicate and reinforce the existing canonical memory
            val existingMemory = similarEntity.toDomain()
            
            val updatedMemory = if (memory.confidenceScore == 5 && memory.state == "CONFIRMED") {
                // G. Explicit Remember commands remain special
                existingMemory.copy(
                    confidenceScore = 5,
                    state = "CONFIRMED",
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    lastAccessedTimestamp = System.currentTimeMillis()
                )
            } else {
                // G. Implicit or candidate additions
                val newConfidence = (existingMemory.confidenceScore + 1).coerceAtMost(5)
                val newState = when (existingMemory.state) {
                    "STALE" -> if (newConfidence >= 3) "CONFIRMED" else "CANDIDATE"
                    "CANDIDATE" -> if (newConfidence >= 3) "CONFIRMED" else "CANDIDATE"
                    else -> existingMemory.state // e.g. CONFIRMED remains CONFIRMED
                }
                existingMemory.copy(
                    confidenceScore = newConfidence,
                    state = newState,
                    lastUpdatedTimestamp = System.currentTimeMillis(),
                    lastAccessedTimestamp = System.currentTimeMillis()
                )
            }

            memoryDao.insertMemory(
                MemoryEntity(
                    id = updatedMemory.id,
                    content = updatedMemory.content,
                    category = updatedMemory.category,
                    createdTimestamp = updatedMemory.createdTimestamp,
                    lastUpdatedTimestamp = updatedMemory.lastUpdatedTimestamp,
                    sourceConversationId = updatedMemory.sourceConversationId,
                    isArchived = updatedMemory.isArchived,
                    confidenceScore = updatedMemory.confidenceScore,
                    lastAccessedTimestamp = updatedMemory.lastAccessedTimestamp,
                    accessCount = updatedMemory.accessCount,
                    state = updatedMemory.state,
                    temporalType = updatedMemory.temporalType,
                    expirationTimestamp = updatedMemory.expirationTimestamp
                )
            )
        } else {
            // No similar active memory exists: insert as a brand new entry
            memoryDao.insertMemory(
                MemoryEntity(
                    id = memory.id,
                    content = memory.content,
                    category = memory.category,
                    createdTimestamp = memory.createdTimestamp,
                    lastUpdatedTimestamp = memory.lastUpdatedTimestamp,
                    sourceConversationId = memory.sourceConversationId,
                    isArchived = memory.isArchived,
                    confidenceScore = memory.confidenceScore,
                    lastAccessedTimestamp = memory.lastAccessedTimestamp,
                    accessCount = memory.accessCount,
                    state = memory.state,
                    temporalType = memory.temporalType,
                    expirationTimestamp = memory.expirationTimestamp
                )
            )
        }
    }

    private fun areMemoriesSimilar(content1: String, content2: String): Boolean {
        val stopwords = setOf(
            "the", "a", "an", "and", "or", "but", "if", "then", "else", "is", "are", "was", "were", 
            "be", "been", "being", "to", "from", "in", "on", "at", "by", "for", "with", "about", 
            "against", "between", "into", "through", "during", "before", "after", "above", "below", 
            "up", "down", "off", "over", "under", "again", "further", "once", "here", "there", 
            "when", "where", "why", "how", "all", "any", "both", "each", "few", "more", "most", 
            "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", 
            "too", "very", "can", "will", "just", "should", "now", "i", "my", "me", "we", "our", 
            "us", "you", "your", "he", "she", "it", "they", "them"
        )

        val clean1 = content1.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        val clean2 = content2.lowercase().replace(Regex("[^a-zA-Z0-9\\s]"), " ").trim()
        
        // Contradiction and negative sentiment shift detection
        val negations = setOf("no longer", "stop", "stopped", "don't", "dont", "do not", "never", "hate", "dislike", "not want", "no more", "quit")
        val hasNeg1 = negations.any { clean1.contains(it) }
        val hasNeg2 = negations.any { clean2.contains(it) }
        if (hasNeg1 != hasNeg2) return false // Opposite sentiments should not merge
        
        val tokens1 = clean1.split("\\s+".toRegex()).filter { it.length > 2 && it !in stopwords }.toSet()
        val tokens2 = clean2.split("\\s+".toRegex()).filter { it.length > 2 && it !in stopwords }.toSet()
        
        if (tokens1.isEmpty() || tokens2.isEmpty()) return false
        
        fun tokensMatch(t1: String, t2: String): Boolean {
            if (t1 == t2) return true
            if (t1.length >= 4 && t2.length >= 4) {
                if (t1.startsWith(t2) && t1.length - t2.length <= 4) return true
                if (t2.startsWith(t1) && t2.length - t1.length <= 4) return true
            }
            return false
        }
        
        val intersectSize = tokens1.count { t1 -> tokens2.any { t2 -> tokensMatch(t1, t2) } }
        val unionSize = tokens1.size + tokens2.size - intersectSize
        val similarity = if (unionSize > 0) intersectSize.toFloat() / unionSize else 0f
        
        return similarity >= 0.65f
    }

    override suspend fun deleteMemory(memory: Memory) {
        memoryDao.deleteMemory(
            MemoryEntity(
                id = memory.id,
                content = memory.content,
                category = memory.category,
                createdTimestamp = memory.createdTimestamp,
                lastUpdatedTimestamp = memory.lastUpdatedTimestamp,
                sourceConversationId = memory.sourceConversationId,
                isArchived = memory.isArchived,
                confidenceScore = memory.confidenceScore,
                lastAccessedTimestamp = memory.lastAccessedTimestamp,
                accessCount = memory.accessCount,
                state = memory.state,
                temporalType = memory.temporalType,
                expirationTimestamp = memory.expirationTimestamp
            )
        )
    }

    override suspend fun deleteMemoryById(id: String) {
        memoryDao.deleteMemoryById(id)
    }

    override suspend fun searchMemories(query: String): List<Memory> {
        return memoryDao.searchMemories(query).map { it.toDomain() }
    }

    private fun MemoryEntity.toDomain(): Memory {
        return Memory(
            id = id,
            content = content,
            category = category,
            createdTimestamp = createdTimestamp,
            lastUpdatedTimestamp = lastUpdatedTimestamp,
            sourceConversationId = sourceConversationId,
            isArchived = isArchived,
            confidenceScore = confidenceScore,
            lastAccessedTimestamp = lastAccessedTimestamp,
            accessCount = accessCount,
            state = state,
            temporalType = temporalType,
            expirationTimestamp = expirationTimestamp
        )
    }
}
