package com.example.ai.tool.search.model

/**
 * Provider-neutral domain request model for performing a web search.
 */
data class WebSearchQuery(
    val query: String,
    val maxResults: Int? = null
)

/**
 * Provider-neutral domain representation of a single search result snippet.
 * Treat all fields as untrusted raw strings.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String? = null,
    val publishedDate: String? = null,
    val relevanceScore: Double? = null
)

/**
 * Provider-neutral response model aggregating results returned by the web search backend.
 */
data class WebSearchResponse(
    val query: String,
    val results: List<WebSearchResult>,
    val isSuccess: Boolean,
    val errorMessage: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
