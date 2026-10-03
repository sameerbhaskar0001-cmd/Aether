package com.example.ai.file

import com.example.ai.file.model.FileImportRequest
import com.example.ai.file.model.FileImportResult
import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument
import com.example.data.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileImportServiceTest {

    private lateinit var fakeRepository: FakeFileRepository
    private lateinit var service: FileImportService

    @Before
    fun setUp() {
        fakeRepository = FakeFileRepository()
        service = FileImportServiceImpl(
            fileRepository = fakeRepository,
            fileTypeDetector = FileTypeDetector(),
            timeProvider = { 1000L },
            idGenerator = { "fixed-test-id" }
        )
    }

    // 1. Metadata Validation
    @Test
    fun testMetadataValidation_BlankFileName() = runBlocking {
        val request = FileImportRequest(
            fileName = "   ",
            fileSize = 100L,
            localUri = "file:///tmp/empty_name.txt"
        )
        val result = service.importFile(request)
        assertTrue(result is FileImportResult.InvalidMetadata)
        assertEquals("File name must not be blank", (result as FileImportResult.InvalidMetadata).reason)
    }

    @Test
    fun testMetadataValidation_ExcessiveFileNameLength() = runBlocking {
        val longName = "a".repeat(256) + ".txt"
        val request = FileImportRequest(
            fileName = longName,
            fileSize = 100L,
            localUri = "file:///tmp/$longName"
        )
        val result = service.importFile(request)
        assertTrue(result is FileImportResult.InvalidMetadata)
        assertTrue((result as FileImportResult.InvalidMetadata).reason.contains("exceeds maximum allowed length"))
    }

    @Test
    fun testMetadataValidation_ZeroOrNegativeFileSize() = runBlocking {
        val zeroSizeRequest = FileImportRequest(
            fileName = "notes.txt",
            fileSize = 0L,
            localUri = "file:///tmp/notes.txt"
        )
        val resultZero = service.importFile(zeroSizeRequest)
        assertTrue(resultZero is FileImportResult.InvalidMetadata)
        assertEquals("File size must be greater than zero bytes", (resultZero as FileImportResult.InvalidMetadata).reason)

        val negativeSizeRequest = FileImportRequest(
            fileName = "notes.txt",
            fileSize = -5L,
            localUri = "file:///tmp/notes.txt"
        )
        val resultNeg = service.importFile(negativeSizeRequest)
        assertTrue(resultNeg is FileImportResult.InvalidMetadata)
    }

    @Test
    fun testMetadataValidation_OversizedFile() = runBlocking {
        val oversized = 50 * 1024 * 1024L + 1L
        val request = FileImportRequest(
            fileName = "huge.txt",
            fileSize = oversized,
            localUri = "file:///tmp/huge.txt"
        )
        val result = service.importFile(request)
        assertTrue(result is FileImportResult.InvalidMetadata)
        assertTrue((result as FileImportResult.InvalidMetadata).reason.contains("exceeds maximum limit of 50 MB"))
    }

    @Test
    fun testMetadataValidation_BlankLocalUri() = runBlocking {
        val request = FileImportRequest(
            fileName = "notes.txt",
            fileSize = 50L,
            localUri = "   "
        )
        val result = service.importFile(request)
        assertTrue(result is FileImportResult.InvalidMetadata)
        assertEquals("Local URI must not be blank", (result as FileImportResult.InvalidMetadata).reason)
    }

    // 2. Content Hash Determinism
    @Test
    fun testContentHashDeterminism() = runBlocking {
        val content = "Aether local intelligence file content".toByteArray(Charsets.UTF_8)
        val result1 = service.importFromBytes(
            fileName = "file1.txt",
            bytes = content,
            localUri = "file:///path/file1.txt",
            isIncognito = true
        )
        val result2 = service.importFromBytes(
            fileName = "file2.txt",
            bytes = content,
            localUri = "file:///different/path.txt",
            isIncognito = true
        )

        assertTrue(result1 is FileImportResult.Success)
        assertTrue(result2 is FileImportResult.Success)

        val hash1 = (result1 as FileImportResult.Success).document.contentHash
        val hash2 = (result2 as FileImportResult.Success).document.contentHash

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun testContentHashDeterminism_DifferentContentYieldsDifferentHash() = runBlocking {
        val contentA = "Alpha content".toByteArray(Charsets.UTF_8)
        val contentB = "Beta content".toByteArray(Charsets.UTF_8)

        val resultA = service.importFromBytes("a.txt", contentA, isIncognito = true)
        val resultB = service.importFromBytes("b.txt", contentB, isIncognito = true)

        val hashA = (resultA as FileImportResult.Success).document.contentHash
        val hashB = (resultB as FileImportResult.Success).document.contentHash

        assertFalse(hashA == hashB)
    }

    // 3. Duplicate File Detection
    @Test
    fun testDuplicateFileDetection() = runBlocking {
        val content = "Repeated document payload".toByteArray(Charsets.UTF_8)

        val firstResult = service.importFromBytes(
            fileName = "original.txt",
            bytes = content,
            localUri = "file:///data/original.txt",
            isIncognito = false
        )
        assertTrue(firstResult is FileImportResult.Success)
        assertFalse((firstResult as FileImportResult.Success).isDuplicate)
        assertEquals(1, fakeRepository.persistedFiles.size)

        val secondResult = service.importFromBytes(
            fileName = "duplicate.txt",
            bytes = content,
            localUri = "file:///data/duplicate.txt",
            isIncognito = false
        )
        assertTrue(secondResult is FileImportResult.Success)
        val secondSuccess = secondResult as FileImportResult.Success

        assertTrue(secondSuccess.isDuplicate)
        assertEquals(firstResult.document.id, secondSuccess.document.id)
        assertEquals(1, fakeRepository.persistedFiles.size)
    }

    // 4. Unsupported File Handling
    @Test
    fun testUnsupportedFileHandling() = runBlocking {
        val binaryBytes = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)
        val result = service.importFromBytes(
            fileName = "installer.exe",
            bytes = binaryBytes,
            localUri = "file:///downloads/installer.exe"
        )

        assertTrue(result is FileImportResult.Unsupported)
        val unsupported = result as FileImportResult.Unsupported
        assertEquals("installer.exe", unsupported.fileName)
        assertEquals(DetectedFileType.UNSUPPORTED, unsupported.detectedType)
        assertEquals(0, fakeRepository.persistedFiles.size)
    }

    // 5. Incognito Isolation
    @Test
    fun testIncognitoIsolation_NeverPersistsToRepository() = runBlocking {
        val content = "Top Secret Incognito Note".toByteArray(Charsets.UTF_8)
        val result = service.importFromBytes(
            fileName = "secret.txt",
            bytes = content,
            localUri = "file:///secret/note.txt",
            isIncognito = true
        )

        assertTrue(result is FileImportResult.Success)
        val doc = (result as FileImportResult.Success).document

        assertEquals("Top Secret Incognito Note", doc.extractedText)
        assertEquals(ExtractionStatus.COMPLETED, doc.extractionStatus)

        assertEquals(0, fakeRepository.persistedFiles.size)
        assertNull(fakeRepository.getFileById(doc.id))
        assertNull(fakeRepository.getFileByContentHash(doc.contentHash))
    }

    @Test
    fun testIncognitoDoesNotInspectPersistentDuplicate() = runBlocking {
        val content = "private content".toByteArray(Charsets.UTF_8)
        val persistent = service.importFromBytes("persistent.txt", content, isIncognito = false)
        assertTrue(persistent is FileImportResult.Success)
        assertEquals(1, fakeRepository.persistedFiles.size)

        val incognito = service.importFromBytes("private-copy.txt", content, isIncognito = true)
        assertTrue(incognito is FileImportResult.Success)
        assertFalse((incognito as FileImportResult.Success).isDuplicate)
        assertEquals("private content", incognito.document.extractedText)
        assertEquals(1, fakeRepository.persistedFiles.size)
    }

    @Test
    fun testNonIncognito_PersistsToRepository() = runBlocking {
        val content = "Standard Persisted Note".toByteArray(Charsets.UTF_8)
        val result = service.importFromBytes(
            fileName = "regular.txt",
            bytes = content,
            localUri = "file:///storage/regular.txt",
            isIncognito = false
        )

        assertTrue(result is FileImportResult.Success)
        val doc = (result as FileImportResult.Success).document

        assertEquals(1, fakeRepository.persistedFiles.size)
        val persisted = fakeRepository.getFileById(doc.id)
        assertNotNull(persisted)
        assertEquals("Standard Persisted Note", persisted?.extractedText)
    }

    // 6. Extraction Status Transitions
    @Test
    fun testPlainTextExtractionStatus() = runBlocking {
        val text = "Hello Aether, this is plain text."
        val result = service.importFromBytes("hello.txt", text.toByteArray(Charsets.UTF_8))
        assertTrue(result is FileImportResult.Success)
        val doc = (result as FileImportResult.Success).document
        assertEquals(ExtractionStatus.COMPLETED, doc.extractionStatus)
        assertEquals(text, doc.extractedText)
    }

    @Test
    fun testImageUnsupportedStatus() = runBlocking {
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val imageResult = service.importFromBytes("chart.png", pngMagic)
        assertTrue(imageResult is FileImportResult.Success)
        val imageDoc = (imageResult as FileImportResult.Success).document
        // Images remain unsupported until Vision phase
        assertEquals(ExtractionStatus.UNSUPPORTED, imageDoc.extractionStatus)
        assertNull(imageDoc.extractedText)
    }

    private class FakeFileRepository : FileRepository {
        val persistedFiles = mutableMapOf<String, FileDocument>()

        override fun getAllFiles(): Flow<List<FileDocument>> =
            flowOf(persistedFiles.values.toList())

        override suspend fun getFileById(id: String): FileDocument? =
            persistedFiles[id]

        override suspend fun getFileByContentHash(contentHash: String): FileDocument? =
            persistedFiles.values.firstOrNull { it.contentHash == contentHash }

        override suspend fun saveFile(fileDocument: FileDocument, isIncognito: Boolean) {
            if (!isIncognito) {
                persistedFiles[fileDocument.id] = fileDocument
            }
        }

        override suspend fun updateExtractionStatus(
            id: String,
            status: ExtractionStatus,
            extractedText: String?,
            updatedAt: Long
        ) {
            val existing = persistedFiles[id] ?: return
            persistedFiles[id] = existing.copy(
                extractionStatus = status,
                extractedText = extractedText,
                updatedAt = updatedAt
            )
        }

        override suspend fun deleteFile(id: String) {
            persistedFiles.remove(id)
        }

        override suspend fun deleteAllFiles() {
            persistedFiles.clear()
        }
    }
}
