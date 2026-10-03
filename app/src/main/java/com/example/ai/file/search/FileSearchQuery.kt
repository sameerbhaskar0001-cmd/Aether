package com.example.ai.file.search

import com.example.data.model.file.FileDocument

data class FileSearchQuery(
    val queryText: String,
    val maxResults: Int = 20,
    val maxSnippetLength: Int = 160,
    val isIncognito: Boolean = false,
    val incognitoDocuments: List<FileDocument> = emptyList()
)
