package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.MemoryExtractionJobEntity

@Dao
interface MemoryExtractionJobDao {
    @Query("SELECT * FROM memory_extraction_jobs WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getJobByConversationId(conversationId: String): MemoryExtractionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateJob(job: MemoryExtractionJobEntity)

    @Query("DELETE FROM memory_extraction_jobs WHERE conversationId = :conversationId")
    suspend fun deleteJobByConversationId(conversationId: String)
}
