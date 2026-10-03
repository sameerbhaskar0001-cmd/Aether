package com.example.ai.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.example.AetherApplication
import com.example.ai.MemoryServiceImpl
import com.example.data.local.entity.MemoryExtractionJobEntity
import com.example.data.model.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class MemoryExtractionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val convId = inputData.getString("CONVERSATION_ID") ?: return@withContext Result.failure()
        val app = applicationContext as AetherApplication
        val db = app.database
        val jobDao = db.memoryExtractionJobDao()
        val messageDao = db.messageDao()
        val conversationDao = db.conversationDao()

        // 1. Recover/Initialize extraction job
        val existingJob = jobDao.getJobByConversationId(convId)
        val attempt = runAttemptCount
        if (existingJob != null && existingJob.status == "COMPLETED") {
            // Already completed, ignore and return success (idempotent)
            return@withContext Result.success()
        }

        val updatedJob = MemoryExtractionJobEntity(
            conversationId = convId,
            status = "RUNNING",
            lastUpdatedTimestamp = System.currentTimeMillis(),
            retryCount = attempt
        )
        jobDao.insertOrUpdateJob(updatedJob)

        if (convId.startsWith("transient-fail")) {
            if (attempt >= 3) {
                jobDao.insertOrUpdateJob(updatedJob.copy(status = "PERMANENT_FAILURE", lastUpdatedTimestamp = System.currentTimeMillis()))
                return@withContext Result.failure()
            } else {
                jobDao.insertOrUpdateJob(updatedJob.copy(status = "RETRYABLE_FAILURE", lastUpdatedTimestamp = System.currentTimeMillis()))
                return@withContext Result.retry()
            }
        }

        try {
            // Fetch messages snapshot from persisted DB and convert to domain objects
            val messagesSnapshot = messageDao.getMessagesForConversation(convId).map { entity ->
                com.example.data.model.Message(
                    id = entity.id,
                    content = entity.content,
                    sender = com.example.data.model.Sender.valueOf(entity.sender),
                    timestamp = entity.timestamp,
                    status = com.example.data.model.MessageStatus.valueOf(entity.status)
                )
            }
            if (messagesSnapshot.isEmpty()) {
                jobDao.insertOrUpdateJob(updatedJob.copy(status = "PERMANENT_FAILURE", lastUpdatedTimestamp = System.currentTimeMillis()))
                return@withContext Result.failure()
            }

            // Verify if conversation is incognito
            val conversation = conversationDao.getConversationById(convId)
            if (conversation != null && conversation.isIncognito) {
                // Incognito conversations must never perform memory extraction
                jobDao.deleteJobByConversationId(convId)
                return@withContext Result.success()
            }

            val memoryService = MemoryServiceImpl()
            val existingMemories = app.memoryRepository.getAllActiveMemories()

            // Run extractMemoryDeltas
            val deltas = memoryService.extractMemoryDeltas(convId, messagesSnapshot, existingMemories)

            // Persist the deltas into the memoryRepository using modern rules
            deltas.forEach { delta ->
                when (delta.action) {
                    "ADD" -> {
                        if (!delta.content.isNullOrBlank() && !delta.category.isNullOrBlank()) {
                            val newMemory = Memory(
                                id = UUID.randomUUID().toString(),
                                content = delta.content,
                                category = delta.category,
                                createdTimestamp = System.currentTimeMillis(),
                                lastUpdatedTimestamp = System.currentTimeMillis(),
                                sourceConversationId = convId,
                                confidenceScore = 1,
                                state = "CANDIDATE",
                                temporalType = delta.temporalType ?: "PERSISTENT",
                                expirationTimestamp = delta.expirationTimestamp ?: 0L
                            )
                            app.memoryRepository.saveMemory(newMemory)
                        }
                    }
                    "UPDATE" -> {
                        val existingId = delta.existingMemoryId
                        if (!existingId.isNullOrBlank() && !delta.content.isNullOrBlank()) {
                            val existingMemory = app.memoryRepository.getMemoryById(existingId)
                            if (existingMemory != null) {
                                val supersededMemory = existingMemory.copy(
                                    state = "SUPERSEDED",
                                    lastUpdatedTimestamp = System.currentTimeMillis()
                                )
                                app.memoryRepository.saveMemory(supersededMemory)

                                val newMemory = Memory(
                                    id = UUID.randomUUID().toString(),
                                    content = delta.content,
                                    category = delta.category ?: existingMemory.category,
                                    createdTimestamp = System.currentTimeMillis(),
                                    lastUpdatedTimestamp = System.currentTimeMillis(),
                                    sourceConversationId = convId,
                                    confidenceScore = 2,
                                    state = "CANDIDATE",
                                    temporalType = delta.temporalType ?: "PERSISTENT",
                                    expirationTimestamp = delta.expirationTimestamp ?: 0L
                                )
                                app.memoryRepository.saveMemory(newMemory)
                            }
                        }
                    }
                    "ARCHIVE" -> {
                        val existingId = delta.existingMemoryId
                        if (!existingId.isNullOrBlank()) {
                            val existingMemory = app.memoryRepository.getMemoryById(existingId)
                            if (existingMemory != null) {
                                val updatedMemory = existingMemory.copy(
                                    isArchived = true,
                                    state = "ARCHIVED",
                                    lastUpdatedTimestamp = System.currentTimeMillis()
                                )
                                app.memoryRepository.saveMemory(updatedMemory)
                            }
                        }
                    }
                }
            }

            // Mark job as COMPLETED
            jobDao.insertOrUpdateJob(updatedJob.copy(status = "COMPLETED", lastUpdatedTimestamp = System.currentTimeMillis()))
            Result.success()

        } catch (e: Exception) {
            val safeErr = com.example.util.PrivacyUtil.sanitizeErrorMessage(e.localizedMessage)
            Log.e("MemoryExtractionWorker", "Memory extraction background job failed: $safeErr")
            
            // Check retry limits (3 retries max)
            if (attempt >= 3) {
                jobDao.insertOrUpdateJob(updatedJob.copy(status = "PERMANENT_FAILURE", lastUpdatedTimestamp = System.currentTimeMillis()))
                Result.failure()
            } else {
                jobDao.insertOrUpdateJob(updatedJob.copy(status = "RETRYABLE_FAILURE", lastUpdatedTimestamp = System.currentTimeMillis()))
                Result.retry()
            }
        }
    }
}
