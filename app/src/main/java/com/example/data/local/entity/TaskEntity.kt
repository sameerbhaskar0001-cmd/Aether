package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val isCompleted: Boolean,
    val dueDate: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
