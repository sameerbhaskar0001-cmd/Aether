package com.example.data.repository

import com.example.data.local.dao.FileDao
import com.example.data.local.entity.toEntity
import com.example.data.local.entity.toModel
import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface FileRepository {
    fun getAllFiles(): Flow<List<FileDocument>>
    suspend fun getFileById(id: String): FileDocument?
    suspend fun getFileByContentHash(contentHash: String): FileDocument?
    suspend fun saveFile(fileDocument: FileDocument, isIncognito: Boolean = false)
    suspend fun updateExtractionStatus(
        id: String,
        status: ExtractionStatus,
        extractedText: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )
    suspend fun deleteFile(id: String)
    suspend fun deleteAllFiles()
}

class FileRepositoryImpl(
    private val fileDao: FileDao
) : FileRepository {

    override fun getAllFiles(): Flow<List<FileDocument>> {
        return fileDao.getAllFilesFlow().map { entities ->
            entities.map { it.toModel() }
        }
    }

    override suspend fun getFileById(id: String): FileDocument? {
        return fileDao.getFileById(id)?.toModel()
    }

    override suspend fun getFileByContentHash(contentHash: String): FileDocument? {
        return fileDao.getFileByContentHash(contentHash)?.toModel()
    }

    override suspend fun saveFile(fileDocument: FileDocument, isIncognito: Boolean) {
        if (!isIncognito) {
            fileDao.insertFile(fileDocument.toEntity())
        }
    }

    override suspend fun updateExtractionStatus(
        id: String,
        status: ExtractionStatus,
        extractedText: String?,
        updatedAt: Long
    ) {
        fileDao.updateExtraction(
            id = id,
            status = status.name,
            extractedText = extractedText,
            updatedAt = updatedAt
        )
    }

    override suspend fun deleteFile(id: String) {
        fileDao.deleteFileById(id)
    }

    override suspend fun deleteAllFiles() {
        fileDao.deleteAllFiles()
    }
}
