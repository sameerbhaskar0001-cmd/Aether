package com.example.ai.tool.search

import com.example.ai.tool.search.model.WebSearchQuery
import com.example.ai.tool.search.model.WebSearchResponse

/**
 * Common, provider-neutral contract for searching external web resources.
 * Concrete implementations will handle actual API connectivity (e.g. Gemini Search, Google Custom Search, Tavily)
 * without leaking provider details into the tool execution layer.
 */
interface WebSearchBackend {
    /**
     * Executes the search query and returns structured results.
     * Should handle internal API errors gracefully and return a [WebSearchResponse] with success=false.
     */
    suspend fun search(query: WebSearchQuery): WebSearchResponse
}
