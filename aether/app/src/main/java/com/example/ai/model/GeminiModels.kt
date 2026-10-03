package com.example.ai.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    @Json(name = "contents") val contents: List<Content>,
    @Json(name = "generationConfig") val generationConfig: GenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: Content? = null,
    @Json(name = "tools") val tools: List<GeminiToolConfig>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionDeclaration(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String,
    @Json(name = "parameters") val parameters: GeminiSchema? = null
)

@JsonClass(generateAdapter = true)
data class GeminiSchema(
    @Json(name = "type") val type: String,
    @Json(name = "properties") val properties: Map<String, GeminiSchema>? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "required") val required: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiToolConfig(
    @Json(name = "googleSearch") val googleSearch: Map<String, Any>? = null,
    @Json(name = "functionDeclarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    @Json(name = "parts") val parts: List<Part>,
    @Json(name = "role") val role: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionCall(
    @Json(name = "name") val name: String,
    @Json(name = "args") val args: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionResponse(
    @Json(name = "name") val name: String,
    @Json(name = "response") val response: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class Part(
    @Json(name = "text") val text: String? = null,
    @Json(name = "functionCall") val functionCall: GeminiFunctionCall? = null,
    @Json(name = "functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    @Json(name = "temperature") val temperature: Float? = null,
    @Json(name = "topP") val topP: Float? = null,
    @Json(name = "topK") val topK: Int? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    @Json(name = "candidates") val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    @Json(name = "content") val content: Content? = null,
    @Json(name = "finishReason") val finishReason: String? = null,
    @Json(name = "groundingMetadata") val groundingMetadata: GroundingMetadata? = null
)

@JsonClass(generateAdapter = true)
data class GroundingMetadata(
    @Json(name = "webSearchQueries") val webSearchQueries: List<String>? = null,
    @Json(name = "groundingChunks") val groundingChunks: List<GroundingChunk>? = null
)

@JsonClass(generateAdapter = true)
data class GroundingChunk(
    @Json(name = "web") val web: WebSource? = null
)

@JsonClass(generateAdapter = true)
data class WebSource(
    @Json(name = "uri") val uri: String? = null,
    @Json(name = "title") val title: String? = null
)
