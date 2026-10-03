package com.example.ai.provider.groq

import com.example.ai.provider.groq.models.GroqChatCompletionRequest
import com.example.ai.provider.groq.models.GroqChatCompletionResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface GroqApiService {
    @Streaming
    @POST("chat/completions")
    suspend fun generateChatCompletionStream(
        @Header("Authorization") authHeader: String,
        @Body request: GroqChatCompletionRequest
    ): ResponseBody

    @POST("chat/completions")
    suspend fun generateChatCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: GroqChatCompletionRequest
    ): GroqChatCompletionResponse
}
