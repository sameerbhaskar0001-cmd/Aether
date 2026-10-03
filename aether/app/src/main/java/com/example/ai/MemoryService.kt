package com.example.ai

import com.example.data.model.Memory
import com.example.data.model.Message
import kotlinx.coroutines.flow.Flow

data class MemoryDelta(
    val action: String, // "ADD", "UPDATE", "ARCHIVE"
    val category: String?,
    val content: String?,
    val existingMemoryId: String? = null,
    val temporalType: String? = "PERSISTENT",
    val expirationTimestamp: Long? = 0L
)

interface MemoryService {
    /**
     * Finds memories from [allMemories] that are semantically or lexically relevant to the [query].
     */
    suspend fun getRelevantMemories(query: String, allMemories: List<Memory>): List<Memory>

    /**
     * Uses Gemini to extract memory updates/additions/deletions from a conversation history and current list of memories.
     */
    suspend fun extractMemoryDeltas(
        conversationId: String,
        messages: List<Message>,
        existingMemories: List<Memory>
    ): List<MemoryDelta>
}
