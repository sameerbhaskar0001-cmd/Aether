package com.example.data.model.file

enum class ExtractionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    UNSUPPORTED
}

data class FileDocument(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val localUri: String,
    val createdAt: Long,
    val updatedAt: Long,
    val extractionStatus: ExtractionStatus,
    val extractedText: String? = null,
    val contentHash: String
)
