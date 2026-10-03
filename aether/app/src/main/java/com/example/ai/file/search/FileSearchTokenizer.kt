package com.example.ai.file.search

import kotlin.math.max
import kotlin.math.min

object FileSearchTokenizer {

    val COMMON_STOPWORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
        "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
        "below", "between", "both", "but", "by", "can't", "cannot", "could", "couldn't",
        "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during",
        "each", "few", "for", "from", "further", "had", "hadn't", "has", "hasn't",
        "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here",
        "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i",
        "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it",
        "it's", "its", "itself", "let's", "me", "more", "most", "mustn't", "my",
        "myself", "no", "nor", "not", "of", "off", "on", "once", "only", "or",
        "other", "ought", "our", "ours", "ourselves", "out", "over", "own", "same",
        "shan't", "she", "she'd", "she'll", "she's", "should", "shouldn't", "so",
        "some", "such", "than", "that", "that's", "the", "their", "theirs", "them",
        "themselves", "then", "there", "there's", "these", "they", "they'd",
        "they'll", "they're", "they've", "this", "those", "through", "to", "too",
        "under", "until", "up", "very", "was", "wasn't", "we", "we'd", "we'll",
        "we're", "we've", "were", "weren't", "what", "what's", "when", "when's",
        "where", "where's", "which", "while", "who", "who's", "whom", "why",
        "why's", "with", "won't", "would", "wouldn't", "you", "you'd", "you'll",
        "you're", "you've", "your", "yours", "yourself", "yourselves"
    )

    fun tokenize(text: String): List<String> {
        val normalized = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ")
        val rawTokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val filtered = rawTokens.filter { it !in COMMON_STOPWORDS }
        return if (filtered.isEmpty() && rawTokens.isNotEmpty()) rawTokens else filtered
    }

    fun computeScore(queryText: String, fileName: String, extractedText: String): Float {
        val queryTokens = tokenize(queryText)
        if (queryTokens.isEmpty()) return 0f

        val docTokens = tokenize(extractedText)
        val docTokenSet = docTokens.toSet()
        val matchCount = queryTokens.count { it in docTokenSet }
        val normQuery = queryText.lowercase().trim().replace(Regex("\\s+"), " ")
        val normText = extractedText.lowercase().trim().replace(Regex("\\s+"), " ")

        val fileNameMatch = fileName.lowercase().contains(normQuery) || queryTokens.any { fileName.lowercase().contains(it) }

        if (matchCount == 0 && !fileNameMatch) {
            return 0f
        }

        val overlapRatio = if (queryTokens.isNotEmpty()) matchCount.toFloat() / queryTokens.size.toFloat() else 0f
        var score = overlapRatio * 1.0f

        // Exact phrase match boost
        if (normQuery.isNotBlank() && normText.contains(normQuery)) {
            score += 0.5f
        }

        // Filename match boost
        if (fileNameMatch) {
            score += 0.3f
        }

        return max(score, 0.1f) // Ensure positive score for matches
    }

    fun generateSnippet(queryText: String, extractedText: String, maxSnippetLength: Int): String {
        if (extractedText.isBlank()) return ""

        val normQuery = queryText.lowercase().trim().replace(Regex("\\s+"), " ")
        val lowerText = extractedText.lowercase()

        val matchIndex = if (normQuery.isNotBlank()) lowerText.indexOf(normQuery) else -1
        val targetIndex = if (matchIndex != -1) {
            matchIndex
        } else {
            val queryTokens = tokenize(queryText)
            var firstIdx = -1
            for (token in queryTokens) {
                val idx = lowerText.indexOf(token)
                if (idx != -1) {
                    if (firstIdx == -1 || idx < firstIdx) {
                        firstIdx = idx
                    }
                }
            }
            firstIdx
        }

        val start = if (targetIndex != -1) {
            max(0, targetIndex - maxSnippetLength / 3)
        } else {
            0
        }

        val end = min(extractedText.length, start + maxSnippetLength)
        var snippet = extractedText.substring(start, end).trim().replace(Regex("\\s+"), " ")

        if (start > 0) {
            snippet = "...$snippet"
        }
        if (end < extractedText.length) {
            snippet = "$snippet..."
        }

        return snippet
    }
}
