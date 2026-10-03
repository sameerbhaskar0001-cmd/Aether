package com.example.ai.tool.search

import com.example.ai.tool.search.model.WebSearchQuery
import com.example.ai.tool.search.model.WebSearchResponse
import com.example.ai.tool.search.model.WebSearchResult

/**
 * Deterministic, network-isolated backend for unit testing the search pipeline.
 */
class FakeWebSearchBackend : WebSearchBackend {

    var shouldFail = false
    var failureMessage = "Search rate limit exceeded."
    var customResults: List<WebSearchResult>? = null

    override suspend fun search(query: WebSearchQuery): WebSearchResponse {
        if (shouldFail) {
            return WebSearchResponse(
                query = query.query,
                results = emptyList(),
                isSuccess = false,
                errorMessage = failureMessage
            )
        }

        val results = customResults ?: listOf(
            WebSearchResult(
                title = "Aether Cognitive Workspace",
                url = "https://aether.workspace.dev",
                snippet = "Aether is an autonomous cognitive workspace assistant built with complete focus, Zero Noise architecture, and privacy in mind.",
                source = "Aether Dev Team",
                publishedDate = "2026-09-25"
            ),
            WebSearchResult(
                title = "Jetpack Compose Guides",
                url = "https://developer.android.com/compose",
                snippet = "Learn Jetpack Compose for modern Android user interface development.",
                source = "Android Developers",
                publishedDate = "2025-11-12"
            )
        )

        return WebSearchResponse(
            query = query.query,
            results = results,
            isSuccess = true
        )
    }
}
