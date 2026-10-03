package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY lastUpdatedTimestamp DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE category = :category AND isArchived = 0")
    suspend fun getMemoriesByCategory(category: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE isArchived = 0")
    suspend fun getAllActiveMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE isArchived = 0 AND state != 'ARCHIVED' AND state != 'SUPERSEDED' AND (expirationTimestamp = 0 OR expirationTimestamp > :currentTimeMillis)")
    suspend fun getAllActiveMemoriesOptimized(currentTimeMillis: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE isArchived = 0 AND (content LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%')")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)
}
