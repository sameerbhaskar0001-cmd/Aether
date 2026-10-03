package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument

@Entity(
    tableName = "file_documents",
    indices = [
        Index(value = ["contentHash"]),
        Index(value = ["createdAt"])
    ]
)
data class FileDocumentEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val localUri: String,
    val createdAt: Long,
    val updatedAt: Long,
    val extractionStatus: String,
    val extractedText: String?,
    val contentHash: String
)

fun FileDocumentEntity.toModel(): FileDocument = FileDocument(
    id = id,
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    localUri = localUri,
    createdAt = createdAt,
    updatedAt = updatedAt,
    extractionStatus = try {
        ExtractionStatus.valueOf(extractionStatus)
    } catch (_: Exception) {
        ExtractionStatus.PENDING
    },
    extractedText = extractedText,
    contentHash = contentHash
)

fun FileDocument.toEntity(): FileDocumentEntity = FileDocumentEntity(
    id = id,
    fileName = fileName,
    mimeType = mimeType,
    fileSize = fileSize,
    localUri = localUri,
    createdAt = createdAt,
    updatedAt = updatedAt,
    extractionStatus = extractionStatus.name,
    extractedText = extractedText,
    contentHash = contentHash
)
