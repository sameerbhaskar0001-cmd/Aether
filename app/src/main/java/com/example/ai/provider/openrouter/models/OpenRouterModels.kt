package com.example.ai.provider.openrouter.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenRouterChatRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<OpenRouterChatMessage>,
    @Json(name = "stream") val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OpenRouterChatMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class OpenRouterChatResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "choices") val choices: List<OpenRouterChoice>? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterChoice(
    @Json(name = "index") val index: Int? = null,
    @Json(name = "message") val message: OpenRouterChatMessage? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterStreamChunkResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "choices") val choices: List<OpenRouterStreamChoice>? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterStreamChoice(
    @Json(name = "index") val index: Int? = null,
    @Json(name = "delta") val delta: OpenRouterStreamDelta? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterStreamDelta(
    @Json(name = "role") val role: String? = null,
    @Json(name = "content") val content: String? = null
)
