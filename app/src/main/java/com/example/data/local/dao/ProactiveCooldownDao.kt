package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.ProactiveCooldownEntity

@Dao
interface ProactiveCooldownDao {
    @Query("SELECT * FROM proactive_cooldowns WHERE signalId = :signalId LIMIT 1")
    suspend fun getCooldown(signalId: String): ProactiveCooldownEntity?

    @Query("SELECT * FROM proactive_cooldowns")
    suspend fun getAllCooldowns(): List<ProactiveCooldownEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCooldown(entity: ProactiveCooldownEntity)

    @Query("DELETE FROM proactive_cooldowns WHERE signalId = :signalId")
    suspend fun deleteCooldown(signalId: String)

    @Query("DELETE FROM proactive_cooldowns")
    suspend fun clearAll()
}
