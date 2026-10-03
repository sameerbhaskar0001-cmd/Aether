package com.example.ai.file.search

interface FileSearchRepository {
    suspend fun searchFiles(query: FileSearchQuery): List<FileSearchResult>
}
