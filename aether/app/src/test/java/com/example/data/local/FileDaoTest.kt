package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.dao.FileDao
import com.example.data.local.entity.FileDocumentEntity
import com.example.data.local.entity.toEntity
import com.example.data.local.entity.toModel
import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument
import com.example.data.repository.FileRepository
import com.example.data.repository.FileRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var fileDao: FileDao
    private lateinit var repository: FileRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fileDao = db.fileDao()
        repository = FileRepositoryImpl(fileDao)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertAndRetrieveFile() = runBlocking {
        val doc = FileDocument(
            id = "test-doc-1",
            fileName = "spec.txt",
            mimeType = "text/plain",
            fileSize = 1024L,
            localUri = "file:///data/spec.txt",
            createdAt = 1000L,
            updatedAt = 1000L,
            extractionStatus = ExtractionStatus.COMPLETED,
            extractedText = "Architecture details for Aether.",
            contentHash = "hash123"
        )

        repository.saveFile(doc, isIncognito = false)

        val retrieved = repository.getFileById("test-doc-1")
        assertNotNull(retrieved)
        assertEquals("spec.txt", retrieved?.fileName)
        assertEquals("Architecture details for Aether.", retrieved?.extractedText)
        assertEquals(ExtractionStatus.COMPLETED, retrieved?.extractionStatus)

        val retrievedByHash = repository.getFileByContentHash("hash123")
        assertNotNull(retrievedByHash)
        assertEquals("test-doc-1", retrievedByHash?.id)
    }

    @Test
    fun testIncognitoIsolationInRepository() = runBlocking {
        val doc = FileDocument(
            id = "incognito-doc",
            fileName = "private.txt",
            mimeType = "text/plain",
            fileSize = 256L,
            localUri = "file:///private/private.txt",
            createdAt = 2000L,
            updatedAt = 2000L,
            extractionStatus = ExtractionStatus.COMPLETED,
            extractedText = "Private incognito text",
            contentHash = "private-hash"
        )

        repository.saveFile(doc, isIncognito = true)

        // Must NOT exist in database
        assertNull(repository.getFileById("incognito-doc"))
        assertNull(repository.getFileByContentHash("private-hash"))
        val all = repository.getAllFiles().first()
        assertEquals(0, all.size)
    }

    @Test
    fun testUpdateExtractionStatus() = runBlocking {
        val doc = FileDocument(
            id = "pending-doc",
            fileName = "doc.pdf",
            mimeType = "application/pdf",
            fileSize = 4096L,
            localUri = "file:///data/doc.pdf",
            createdAt = 1000L,
            updatedAt = 1000L,
            extractionStatus = ExtractionStatus.PENDING,
            extractedText = null,
            contentHash = "pdf-hash"
        )

        repository.saveFile(doc, isIncognito = false)

        repository.updateExtractionStatus(
            id = "pending-doc",
            status = ExtractionStatus.COMPLETED,
            extractedText = "Parsed PDF text content",
            updatedAt = 2000L
        )

        val updated = repository.getFileById("pending-doc")
        assertNotNull(updated)
        assertEquals(ExtractionStatus.COMPLETED, updated?.extractionStatus)
        assertEquals("Parsed PDF text content", updated?.extractedText)
        assertEquals(2000L, updated?.updatedAt)
    }
}
