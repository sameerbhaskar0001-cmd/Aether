package com.example.ai.proactive

import com.example.ai.TimeProvider
import com.example.ai.SystemTimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ProactiveDeliveryCoordinator(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) {
    // Thread-safe repository of deliveries
    private val deliveryRegistry = ConcurrentHashMap<String, ProactiveDelivery>()

    /**
     * Attempts to create and register a ProactiveDelivery for an approved PROACT decision.
     * Returns the created delivery, or null if STAY_SILENT, duplicate, expired, or incognito.
     */
    suspend fun deliver(
        signal: ProactiveSignal,
        decision: ProactiveDecision,
        isIncognito: Boolean
    ): ProactiveDelivery? = withContext(Dispatchers.Default) {
        if (isIncognito) {
            return@withContext null
        }

        // If Phase 8A says STAY_SILENT, coordinator must stay silent
        if (decision.decision == ProactiveAction.STAY_SILENT) {
            return@withContext null
        }

        val currentTime = timeProvider.currentTimeMillis()

        // Respect signal expiration
        if (signal.expirationTimestamp != null && currentTime >= signal.expirationTimestamp) {
            return@withContext null
        }

        val deliveryId = "del_${signal.id}"

        // Duplicate suppression is handled atomically during registration via putIfAbsent

        // Generate deterministic message/title based on signal type
        val title = when (signal.type) {
            ProactiveSignalType.GOAL_RELEVANT -> "Relevant Goal"
            ProactiveSignalType.PROJECT_RELEVANT -> "Active Project"
            ProactiveSignalType.UPCOMING_EVENT -> "Upcoming Event"
            ProactiveSignalType.UNFINISHED_ITEM -> "Unfinished Task"
            ProactiveSignalType.REPEATED_CONCERN -> "Repeated Topic"
            ProactiveSignalType.FUTURE_INTENTION -> "Planned Activity"
            ProactiveSignalType.IMPORTANT_DECISION -> "Past Decision"
        }

        val message = when (signal.type) {
            ProactiveSignalType.GOAL_RELEVANT -> "Your current conversation connects to an active goal."
            ProactiveSignalType.PROJECT_RELEVANT -> "This looks relevant to one of your active projects."
            ProactiveSignalType.UPCOMING_EVENT -> "You have an upcoming event that may be relevant now."
            ProactiveSignalType.UNFINISHED_ITEM -> "You have an unfinished item related to this."
            ProactiveSignalType.REPEATED_CONCERN -> "You've brought up this topic repeatedly."
            ProactiveSignalType.FUTURE_INTENTION -> "This connects to something you planned to do."
            ProactiveSignalType.IMPORTANT_DECISION -> "This relates to an important decision you previously made."
        }

        val delivery = ProactiveDelivery(
            id = deliveryId,
            sourceSignalId = signal.id,
            message = message,
            title = title,
            explanation = signal.explanation,
            timestamp = currentTime,
            expirationTimestamp = signal.expirationTimestamp,
            state = ProactiveDeliveryState.PENDING
        )

        val existing = deliveryRegistry.putIfAbsent(deliveryId, delivery)
        if (existing != null) {
            return@withContext null
        }
        delivery
    }

    /**
     * Transitions a delivery's lifecycle state safely in a thread-safe manner.
     */
    fun transitionDeliveryState(deliveryId: String, newState: ProactiveDeliveryState) {
        deliveryRegistry.computeIfPresent(deliveryId) { _, current ->
            current.transitionTo(newState)
        }
    }

    /**
     * Gets a specific active delivery, returning null if it does not exist or has expired.
     */
    fun getDelivery(deliveryId: String): ProactiveDelivery? {
        val delivery = deliveryRegistry[deliveryId] ?: return null
        val currentTime = timeProvider.currentTimeMillis()
        if (delivery.expirationTimestamp != null && currentTime >= delivery.expirationTimestamp) {
            return null
        }
        return delivery
    }

    /**
     * Gets all current deliveries, excluding expired ones.
     */
    fun getAllDeliveries(): List<ProactiveDelivery> {
        val currentTime = timeProvider.currentTimeMillis()
        return deliveryRegistry.values.filter { delivery ->
            delivery.expirationTimestamp == null || currentTime < delivery.expirationTimestamp
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Clears registry (useful for testing).
     */
    fun clearRegistry() {
        deliveryRegistry.clear()
    }
}
