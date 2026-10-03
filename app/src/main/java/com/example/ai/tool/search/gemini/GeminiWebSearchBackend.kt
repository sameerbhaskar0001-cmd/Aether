package com.example.ai.tool.search.gemini

import com.example.BuildConfig
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.GeminiToolConfig
import com.example.ai.model.Part
import com.example.ai.network.GeminiApiService
import com.example.ai.network.RetrofitClient
import com.example.ai.tool.search.WebSearchBackend
import com.example.ai.tool.search.model.WebSearchQuery
import com.example.ai.tool.search.model.WebSearchResponse
import com.example.ai.tool.search.model.WebSearchResult
import com.example.util.PrivacyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

/**
 * Gemini-specific implementation of [WebSearchBackend] utilizing Gemini's Google Search
 * grounding tool capabilities to return grounded search sources.
 */
class GeminiWebSearchBackend(
    private val apiService: GeminiApiService = RetrofitClient.service,
    private val apiKeyProvider: () -> String = { BuildConfig.GEMINI_API_KEY }
) : WebSearchBackend {

    override suspend fun search(query: WebSearchQuery): WebSearchResponse = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().trim()

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext WebSearchResponse(
                query = query.query,
                results = emptyList(),
                isSuccess = false,
                errorMessage = "Gemini API key is not configured. Please supply a valid key."
            )
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(Part(text = "Search the web for up-to-date information on: ${query.query}"))
                )
            ),
            tools = listOf(GeminiToolConfig(googleSearch = emptyMap()))
        )

        try {
            val response = apiService.generateContent(apiKey, request)
            val candidate = response.candidates?.firstOrNull()

            val candidateText = candidate?.content?.parts?.firstOrNull()?.text?.trim() ?: ""
            val groundingMetadata = candidate?.groundingMetadata
            val chunks = groundingMetadata?.groundingChunks ?: emptyList()

            val searchResults = mutableListOf<WebSearchResult>()

            if (chunks.isNotEmpty()) {
                chunks.forEach { chunk ->
                    val web = chunk.web
                    val uri = web?.uri?.trim() ?: ""
                    val title = web?.title?.trim()?.ifEmpty { "Grounded Search Result" } ?: "Grounded Search Result"
                    val domain = extractDomain(uri)

                    searchResults.add(
                        WebSearchResult(
                            title = title,
                            url = uri,
                            snippet = candidateText.ifEmpty { title },
                            source = domain ?: "Google Search",
                            publishedDate = null
                        )
                    )
                }
            } else if (candidateText.isNotBlank()) {
                // Fallback to grounded candidate summary if grounding chunks are omitted by API
                val encodedQuery = try { URLEncoder.encode(query.query, "UTF-8") } catch (_: Exception) { "" }
                searchResults.add(
                    WebSearchResult(
                        title = "Google Search Summary",
                        url = if (encodedQuery.isNotBlank()) "https://www.google.com/search?q=$encodedQuery" else "https://www.google.com",
                        snippet = candidateText,
                        source = "Google Search",
                        publishedDate = null
                    )
                )
            }

            val maxResults = query.maxResults ?: 5
            val limitedResults = searchResults.take(maxResults)

            WebSearchResponse(
                query = query.query,
                results = limitedResults,
                isSuccess = true,
                errorMessage = null,
                metadata = mapOf(
                    "provider" to "Gemini",
                    "webSearchQueries" to (groundingMetadata?.webSearchQueries ?: emptyList<String>())
                )
            )
        } catch (e: Exception) {
            val safeMessage = mapExceptionToSafeErrorMessage(e)
            WebSearchResponse(
                query = query.query,
                results = emptyList(),
                isSuccess = false,
                errorMessage = safeMessage
            )
        }
    }

    private fun extractDomain(uriString: String): String? {
        if (uriString.isBlank()) return null
        return try {
            val host = URI(uriString).host
            if (!host.isNullOrBlank()) host.removePrefix("www.") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun mapExceptionToSafeErrorMessage(e: Exception): String {
        val sanitizedMsg = PrivacyUtil.sanitizeErrorMessage(e.localizedMessage ?: e.message ?: "Unknown error")
        return when (e) {
            is HttpException -> {
                when (e.code()) {
                    401, 403 -> "Authentication failed while contacting search provider."
                    429 -> "Search request rate limit exceeded."
                    in 500..599 -> "Search service temporarily unavailable."
                    else -> "Search request failed with HTTP error ${e.code()}."
                }
            }
            is IOException -> "Network error during search: $sanitizedMsg"
            else -> "Gemini search failed: $sanitizedMsg"
        }
    }
}
