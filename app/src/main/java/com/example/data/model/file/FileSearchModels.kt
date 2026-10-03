package com.example.data.model.file

data class FileSearchQuery(
    val query: String,
    val maxResults: Int = 10
)

data class FileSearchResult(
    val fileId: String,
    val fileName: String,
    val score: Double,
    val snippet: String,
    val mimeType: String
)
