package com.example.ai.file.extractor

import java.io.InputStream

interface FileTextExtractor {
    suspend fun extractText(
        contentBytes: ByteArray?,
        contentStreamProvider: (() -> InputStream)?,
        mimeType: String,
        fileName: String
    ): ExtractionResult

    sealed class ExtractionResult {
        data class Success(val text: String) : ExtractionResult()
        data class Unsupported(val reason: String) : ExtractionResult()
        data class Failed(val reason: String, val cause: Throwable? = null) : ExtractionResult()
    }
}
