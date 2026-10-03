package com.example.ai.proactive

enum class ProactiveTriggerEvent {
    MEANINGFUL_USER_MESSAGE,
    ASSISTANT_RESPONSE_COMPLETED,
    TOPIC_CHANGED,
    TURN_SEQUENCE_BOUNDARY,
    EXPLICIT_EVALUATION
}
