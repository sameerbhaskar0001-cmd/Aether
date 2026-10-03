package com.example.data.repository

import com.example.data.local.dao.FileDao
import com.example.data.model.file.FileSearchQuery
import com.example.data.model.file.FileSearchResult
import kotlinx.coroutines.flow.first

class LocalFileSearchRepository(
    private val fileDao: FileDao
) : FileSearchRepository {
    
    // Simple stop words
    private val stopWords = setOf("a", "an", "the", "and", "or", "but", "is", "of", "to", "in", "with", "it", "that", "this")
    
    override suspend fun searchFiles(searchQuery: FileSearchQuery): List<FileSearchResult> {
        // Fetch all files
        val allFiles = fileDao.getAllFilesFlow().first()
        
        // Filter
        val candidates = allFiles.filter { 
            it.extractionStatus == "COMPLETED" && 
            !it.extractedText.isNullOrBlank()
        }
        
        val queryTokens = tokenize(searchQuery.query)
        if (queryTokens.isEmpty()) return emptyList()
        
        // Score
        val results = candidates.mapNotNull { entity ->
            val text = entity.extractedText!!
            val score = calculateScore(queryTokens, searchQuery.query, text)
            
            if (score > 0) {
                FileSearchResult(
                    fileId = entity.id,
                    fileName = entity.fileName,
                    score = score,
                    snippet = createSnippet(text, searchQuery.query, 100),
                    mimeType = entity.mimeType
                )
            } else {
                null
            }
        }
        
        // Sort and limit
        return results.sortedWith(
            compareByDescending<FileSearchResult> { it.score }
                .thenBy { it.fileName }
        ).take(searchQuery.maxResults)
    }
    
    private fun tokenize(text: String): List<String> {
        return text.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() && it !in stopWords }
    }
    
    private fun calculateScore(queryTokens: List<String>, query: String, text: String): Double {
        val textTokens = tokenize(text)
        var score = 0.0
        
        // Token overlap
        for (token in queryTokens) {
            score += textTokens.count { it == token }.toDouble()
        }
        
        // Phrase boost
        if (query.trim().contains(" ")) {
            if (text.lowercase().contains(query.lowercase())) {
                score *= 1.5
            }
        }
        
        return score
    }
    
    private fun createSnippet(text: String, query: String, maxLength: Int): String {
        // Simple snippet generation
        val index = text.lowercase().indexOf(query.lowercase())
        val start = if (index != -1) (index - 20).coerceAtLeast(0) else 0
        val end = (start + maxLength).coerceAtMost(text.length)
        
        return (if (start > 0) "..." else "") + text.substring(start, end) + if (end < text.length) "..." else ""
    }
}
