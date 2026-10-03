package com.example.ai.file.model

import com.example.ai.file.DetectedFileType
import com.example.data.model.file.FileDocument
import java.io.InputStream

data class FileImportRequest(
    val fileName: String,
    val mimeType: String? = null,
    val fileSize: Long,
    val localUri: String,
    val contentBytes: ByteArray? = null,
    val contentStreamProvider: (() -> InputStream)? = null,
    val isIncognito: Boolean = false
)

sealed class FileImportResult {
    data class Success(
        val document: FileDocument,
        val isDuplicate: Boolean = false
    ) : FileImportResult()

    data class Unsupported(
        val fileName: String,
        val detectedType: DetectedFileType,
        val reason: String
    ) : FileImportResult()

    data class InvalidMetadata(
        val fileName: String?,
        val reason: String
    ) : FileImportResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : FileImportResult()
}
