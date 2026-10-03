package com.example.data.repository

import com.example.data.model.file.FileSearchQuery
import com.example.data.model.file.FileSearchResult

interface FileSearchRepository {
    suspend fun searchFiles(searchQuery: FileSearchQuery): List<FileSearchResult>
}
