package com.example.ai.file.search

data class FileSearchResult(
    val fileId: String,
    val fileName: String,
    val score: Float,
    val snippet: String,
    val mimeType: String
)
