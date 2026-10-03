package com.example.ai.file.search

import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument
import com.example.data.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSearchRepositoryTest {

    private class FakeFileRepository(
        var files: List<FileDocument> = emptyList()
    ) : FileRepository {
        override fun getAllFiles(): Flow<List<FileDocument>> = flowOf(files)
        override suspend fun getFileById(id: String): FileDocument? = files.find { it.id == id }
        override suspend fun getFileByContentHash(contentHash: String): FileDocument? = files.find { it.contentHash == contentHash }
        override suspend fun saveFile(fileDocument: FileDocument, isIncognito: Boolean) {
            if (!isIncognito) {
                files = files + fileDocument
            }
        }
        override suspend fun updateExtractionStatus(id: String, status: ExtractionStatus, extractedText: String?, updatedAt: Long) {}
        override suspend fun deleteFile(id: String) { files = files.filter { it.id != id } }
        override suspend fun deleteAllFiles() { files = emptyList() }
    }

    private val sampleDoc1 = FileDocument(
        id = "doc-1",
        fileName = "project_roadmap.txt",
        mimeType = "text/plain",
        fileSize = 100L,
        localUri = "content://1",
        createdAt = 1000L,
        updatedAt = 1000L,
        extractionStatus = ExtractionStatus.COMPLETED,
        extractedText = "The 2026 enterprise software architecture roadmap emphasizes cloud migration and modular microservices.",
        contentHash = "hash1"
    )

    private val sampleDoc2 = FileDocument(
        id = "doc-2",
        fileName = "budget_report.pdf",
        mimeType = "application/pdf",
        fileSize = 200L,
        localUri = "content://2",
        createdAt = 2000L,
        updatedAt = 2000L,
        extractionStatus = ExtractionStatus.COMPLETED,
        extractedText = "Q3 financial budget report and enterprise software expenditure details.",
        contentHash = "hash2"
    )

    private val incompleteDoc = FileDocument(
        id = "doc-3",
        fileName = "pending.txt",
        mimeType = "text/plain",
        fileSize = 50L,
        localUri = "content://3",
        createdAt = 3000L,
        updatedAt = 3000L,
        extractionStatus = ExtractionStatus.PENDING,
        extractedText = "Unprocessed text about architecture.",
        contentHash = "hash3"
    )

    @Test
    fun testExactPhraseMatch() = runBlocking {
        val repo = FakeFileRepository(listOf(sampleDoc1, sampleDoc2))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "enterprise software architecture")
        val results = searchRepo.searchFiles(query)

        assertTrue(results.isNotEmpty())
        assertEquals("doc-1", results[0].fileId)
        assertTrue(results[0].score > 1.0f)
    }

    @Test
    fun testTokenMatch() = runBlocking {
        val repo = FakeFileRepository(listOf(sampleDoc1, sampleDoc2))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "migration")
        val results = searchRepo.searchFiles(query)

        assertTrue(results.isNotEmpty())
        assertEquals("doc-1", results[0].fileId)
    }

    @Test
    fun testIrrelevantQuery() = runBlocking {
        val repo = FakeFileRepository(listOf(sampleDoc1, sampleDoc2))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "astrophysics quantum mechanics")
        val results = searchRepo.searchFiles(query)

        assertTrue(results.isEmpty())
    }

    @Test
    fun testMultipleMatchingFilesAndDeterministicOrdering() = runBlocking {
        val docA = sampleDoc1.copy(id = "a", fileName = "alpha.txt", extractedText = "enterprise software system")
        val docB = sampleDoc1.copy(id = "b", fileName = "beta.txt", extractedText = "enterprise software system")

        val repo = FakeFileRepository(listOf(docB, docA))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "enterprise software")
        val results = searchRepo.searchFiles(query)

        assertEquals(2, results.size)
        assertEquals("a", results[0].fileId)
        assertEquals("b", results[1].fileId)
    }

    @Test
    fun testSnippetBounds() = runBlocking {
        val repo = FakeFileRepository(listOf(sampleDoc1))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "architecture", maxSnippetLength = 40)
        val results = searchRepo.searchFiles(query)

        assertTrue(results.isNotEmpty())
        assertTrue(results[0].snippet.length <= 50)
    }

    @Test
    fun testResultLimits() = runBlocking {
        val docs = (1..10).map {
            sampleDoc1.copy(id = "doc-$it", fileName = "file_$it.txt", extractedText = "enterprise software roadmap $it")
        }
        val repo = FakeFileRepository(docs)
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "enterprise", maxResults = 3)
        val results = searchRepo.searchFiles(query)

        assertEquals(3, results.size)
    }

    @Test
    fun testEmptyOrUnsupportedFilesExcluded() = runBlocking {
        val repo = FakeFileRepository(listOf(incompleteDoc))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val query = FileSearchQuery(queryText = "architecture")
        val results = searchRepo.searchFiles(query)

        assertTrue(results.isEmpty())
    }

    @Test
    fun testIncognitoIsolation() = runBlocking {
        val repo = FakeFileRepository(listOf(sampleDoc1))
        val searchRepo = FileSearchRepositoryImpl(repo)

        val incognitoDoc = sampleDoc2.copy(fileName = "secret.txt", extractedText = "incognito classified project details")
        val query = FileSearchQuery(
            queryText = "classified",
            isIncognito = true,
            incognitoDocuments = listOf(incognitoDoc)
        )

        val results = searchRepo.searchFiles(query)

        assertEquals(1, results.size)
        assertEquals("secret.txt", results[0].fileName)
    }
}
