package com.example.ai.tool.search

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.ai.tool.search.model.WebSearchQuery
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool wrapper for Web Search capabilities, exposing the search interface to models
 * under the name "web_search" in a safe and bounded manner.
 */
class WebSearchTool(
    private val backend: WebSearchBackend
) : Tool {

    override val name: String = "web_search"
    override val description: String = "Searches the web for up-to-date information matching the specified query."

    companion object {
        const val MAX_QUERY_LENGTH = 250
        const val DEFAULT_MAX_RESULTS = 5
        const val ABSOLUTE_MAX_RESULTS = 10
        const val MAX_TITLE_LENGTH = 200
        const val MAX_SNIPPET_LENGTH = 1000
    }

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The search query keywords.",
                isRequired = true
            ),
            ToolParameter(
                name = "max_results",
                type = ToolParameterType.NUMBER,
                description = "Optional maximum number of search results to retrieve (min: 1, max: $ABSOLUTE_MAX_RESULTS, default: $DEFAULT_MAX_RESULTS).",
                isRequired = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val rawQuery = arguments["query"]?.toString() ?: ""
        val trimmedQuery = rawQuery.trim()

        if (trimmedQuery.isBlank()) {
            return ToolResult.error(name, "Search query cannot be blank.")
        }

        if (trimmedQuery.length > MAX_QUERY_LENGTH) {
            return ToolResult.error(
                name,
                "Search query exceeds maximum allowed length of $MAX_QUERY_LENGTH characters (received: ${trimmedQuery.length})."
            )
        }

        // Parse max results safely with bounds
        var maxResults = DEFAULT_MAX_RESULTS
        arguments["max_results"]?.let { rawLimit ->
            val limitInt = when (rawLimit) {
                is Number -> rawLimit.toInt()
                is String -> rawLimit.toDoubleOrNull()?.toInt() ?: DEFAULT_MAX_RESULTS
                else -> DEFAULT_MAX_RESULTS
            }
            maxResults = limitInt.coerceIn(1, ABSOLUTE_MAX_RESULTS)
        }

        val searchResponse = try {
            backend.search(WebSearchQuery(query = trimmedQuery, maxResults = maxResults))
        } catch (t: Throwable) {
            return ToolResult.error(name, "Web search execution failed due to backend exception: ${t.localizedMessage ?: t.message}")
        }

        if (!searchResponse.isSuccess) {
            return ToolResult.error(name, searchResponse.errorMessage ?: "Unknown search backend error.")
        }

        // Map and structure results safely, enforcing size limits and treating as untrusted plain text.
        val structuredResults = JSONArray()
        searchResponse.results.take(maxResults).forEach { result ->
            val truncatedTitle = truncate(result.title, MAX_TITLE_LENGTH)
            val truncatedSnippet = truncate(result.snippet, MAX_SNIPPET_LENGTH)
            
            val jsonResult = JSONObject().apply {
                put("title", truncatedTitle)
                put("url", sanitizeUrl(result.url))
                put("snippet", truncatedSnippet)
                result.source?.let { put("source", truncate(it, 100)) }
                result.publishedDate?.let { put("published_date", truncate(it, 50)) }
            }
            structuredResults.put(jsonResult)
        }

        val formattedPayload = structuredResults.toString()
        val meta = mapOf("originalQuery" to trimmedQuery) + searchResponse.metadata

        return ToolResult.success(
            toolName = name,
            content = formattedPayload,
            metadata = meta
        )
    }

    private fun truncate(input: String, maxLength: Int): String {
        return if (input.length > maxLength) {
            input.take(maxLength - 3) + "..."
        } else {
            input
        }
    }

    private fun sanitizeUrl(url: String): String {
        // Enforces basic protocol compliance and prevents script injection or absolute file accesses.
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "unsafe-protocol-redacted"
        }
    }
}
