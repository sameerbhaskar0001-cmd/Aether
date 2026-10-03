package com.example.ai.proactive

enum class ProactiveDeliveryState {
    PENDING,
    DELIVERED,
    ACKNOWLEDGED,
    DISMISSED,
    EXPIRED
}

data class ProactiveDelivery(
    val id: String,
    val sourceSignalId: String,
    val message: String,
    val title: String,
    val explanation: String,
    val timestamp: Long,
    val expirationTimestamp: Long? = null,
    val state: ProactiveDeliveryState = ProactiveDeliveryState.PENDING
) {
    /**
     * Transitions the delivery state. Rejects invalid transitions safely by returning the original object.
     */
    fun transitionTo(newState: ProactiveDeliveryState): ProactiveDelivery {
        val isValid = when (this.state) {
            ProactiveDeliveryState.PENDING -> 
                newState == ProactiveDeliveryState.DELIVERED || newState == ProactiveDeliveryState.EXPIRED
            
            ProactiveDeliveryState.DELIVERED -> 
                newState == ProactiveDeliveryState.ACKNOWLEDGED || 
                newState == ProactiveDeliveryState.DISMISSED || 
                newState == ProactiveDeliveryState.EXPIRED
            
            ProactiveDeliveryState.ACKNOWLEDGED, 
            ProactiveDeliveryState.DISMISSED, 
            ProactiveDeliveryState.EXPIRED -> false
        }
        return if (isValid) {
            this.copy(state = newState)
        } else {
            this
        }
    }
}
