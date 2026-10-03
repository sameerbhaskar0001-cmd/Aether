package com.example.ai.provider.groq.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GroqChatCompletionRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<GroqChatMessage>,
    @Json(name = "stream") val stream: Boolean = false,
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "max_tokens") val maxTokens: Int? = null
)

@JsonClass(generateAdapter = true)
data class GroqChatMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class GroqChatCompletionResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "model") val model: String? = null,
    @Json(name = "choices") val choices: List<GroqChoice>? = null
)

@JsonClass(generateAdapter = true)
data class GroqChoice(
    @Json(name = "index") val index: Int? = null,
    @Json(name = "message") val message: GroqChatMessage? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqStreamChunkResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "model") val model: String? = null,
    @Json(name = "choices") val choices: List<GroqStreamChoice>? = null
)

@JsonClass(generateAdapter = true)
data class GroqStreamChoice(
    @Json(name = "index") val index: Int? = null,
    @Json(name = "delta") val delta: GroqStreamDelta? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GroqStreamDelta(
    @Json(name = "role") val role: String? = null,
    @Json(name = "content") val content: String? = null
)
