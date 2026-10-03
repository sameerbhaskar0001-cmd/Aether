package com.example.ai.file.search

/**
 * Converts local search results into a small, deterministic provider context.
 * File contents are never sent wholesale; only bounded snippets are included.
 */
object FileContextBuilder {
    const val MAX_RESULTS = 5
    const val MAX_TOTAL_CHARS = 6000
    const val MAX_SNIPPET_CHARS = 1200

    fun build(results: List<FileSearchResult>): String? {
        if (results.isEmpty()) return null

        val builder = StringBuilder()
        builder.append("The following excerpts came from the user's locally indexed files.\n")
        builder.append("Use only these excerpts for file-specific factual claims. When useful, identify the source file by its provided name. If the excerpts do not contain enough information, say so instead of inventing details.\n\n")

        var used = builder.length
        results.take(MAX_RESULTS).forEachIndexed { index, result ->
            val snippet = result.snippet.take(MAX_SNIPPET_CHARS).trim()
            if (snippet.isBlank()) return@forEachIndexed

            val block = "[File ${index + 1}: ${sanitizeFileName(result.fileName)}]\n$snippet\n\n"
            if (used + block.length <= MAX_TOTAL_CHARS) {
                builder.append(block)
                used += block.length
            }
        }

        return builder.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\r\\n\\t]"), " ").trim().take(180)
}
