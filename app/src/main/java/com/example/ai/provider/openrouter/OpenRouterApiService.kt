package com.example.ai.provider.openrouter

import com.example.ai.provider.openrouter.models.OpenRouterChatRequest
import com.example.ai.provider.openrouter.models.OpenRouterChatResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface OpenRouterApiService {

    @POST("chat/completions")
    suspend fun generateChatCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: OpenRouterChatRequest
    ): OpenRouterChatResponse

    @Streaming
    @POST("chat/completions")
    suspend fun generateChatCompletionStream(
        @Header("Authorization") authHeader: String,
        @Body request: OpenRouterChatRequest
    ): ResponseBody
}
