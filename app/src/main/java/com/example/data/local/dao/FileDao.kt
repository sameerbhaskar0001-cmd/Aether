package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.FileDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Query("SELECT * FROM file_documents ORDER BY createdAt DESC")
    fun getAllFilesFlow(): Flow<List<FileDocumentEntity>>

    @Query("SELECT * FROM file_documents WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): FileDocumentEntity?

    @Query("SELECT * FROM file_documents WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getFileByContentHash(contentHash: String): FileDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileDocumentEntity)

    @Update
    suspend fun updateFile(file: FileDocumentEntity)

    @Query("UPDATE file_documents SET extractionStatus = :status, extractedText = :extractedText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateExtraction(id: String, status: String, extractedText: String?, updatedAt: Long)

    @Delete
    suspend fun deleteFile(file: FileDocumentEntity)

    @Query("DELETE FROM file_documents WHERE id = :id")
    suspend fun deleteFileById(id: String)

    @Query("DELETE FROM file_documents")
    suspend fun deleteAllFiles()
}
