package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists proactive intelligence cooldown timestamps across app restarts
 * so duplicate suggestions remain blocked within their cooldown window.
 */
@Entity(tableName = "proactive_cooldowns")
data class ProactiveCooldownEntity(
    @PrimaryKey val signalId: String,
    val lastSurfacedTimestamp: Long
)
