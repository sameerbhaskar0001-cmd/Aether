package com.example.ai.file

import com.example.ai.file.extractor.FileTextExtractor
import com.example.ai.file.extractor.PlainTextExtractor
import com.example.ai.file.extractor.PdfTextExtractor
import com.example.ai.file.model.FileImportRequest
import com.example.ai.file.model.FileImportResult
import com.example.data.model.file.ExtractionStatus
import com.example.data.model.file.FileDocument
import com.example.data.repository.FileRepository
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

interface FileImportService {
    suspend fun importFile(request: FileImportRequest): FileImportResult

    suspend fun importFromBytes(
        fileName: String,
        bytes: ByteArray,
        mimeType: String? = null,
        localUri: String = "memory://$fileName",
        isIncognito: Boolean = false
    ): FileImportResult
}

class FileImportServiceImpl(
    private val fileRepository: FileRepository,
    private val fileTypeDetector: FileTypeDetector = FileTypeDetector(),
    private val plainTextExtractor: FileTextExtractor = PlainTextExtractor(),
    private val pdfTextExtractor: FileTextExtractor = PdfTextExtractor(),
    private val docxTextExtractor: FileTextExtractor = com.example.ai.file.extractor.DocxTextExtractor(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : FileImportService {

    override suspend fun importFromBytes(
        fileName: String,
        bytes: ByteArray,
        mimeType: String?,
        localUri: String,
        isIncognito: Boolean
    ): FileImportResult {
        return importFile(
            FileImportRequest(
                fileName = fileName,
                mimeType = mimeType,
                fileSize = bytes.size.toLong(),
                localUri = localUri,
                contentBytes = bytes,
                isIncognito = isIncognito
            )
        )
    }

    override suspend fun importFile(request: FileImportRequest): FileImportResult {
        // 1. Metadata Validation
        val validationError = validateMetadata(request)
        if (validationError != null) {
            return validationError
        }

        // 2. Read header bytes (first 32 bytes) for magic-byte detection
        val headerBytes = extractHeaderBytes(request)

        // 3. Detect File Type
        val detectedType = fileTypeDetector.detect(
            mimeType = request.mimeType,
            fileName = request.fileName,
            headerBytes = headerBytes
        )

        if (detectedType == DetectedFileType.UNSUPPORTED) {
            return FileImportResult.Unsupported(
                fileName = request.fileName,
                detectedType = detectedType,
                reason = "Unsupported file type for ${request.fileName}"
            )
        }

        // 4. Calculate Deterministic Content Hash (SHA-256)
        val contentHash = calculateContentHash(request)

        // 5. Duplicate File Detection
        // Incognito must not inspect or reveal the existence of persistent files.
        if (!request.isIncognito) {
            val existing = fileRepository.getFileByContentHash(contentHash)
            if (existing != null) {
                val now = timeProvider()
                val updatedExisting = existing.copy(updatedAt = now)
                fileRepository.saveFile(updatedExisting, isIncognito = false)
                return FileImportResult.Success(
                    document = updatedExisting,
                    isDuplicate = true
                )
            }
        }

        val canonicalMime = fileTypeDetector.resolveCanonicalMimeType(
            detectedType = detectedType,
            originalMime = request.mimeType,
            fileName = request.fileName
        )

        // 6. Extraction Status and Text Extraction (Plain Text & PDF supported in Part 2)
        val (extractionStatus, extractedText) = when (detectedType) {
            DetectedFileType.PLAIN_TEXT -> {
                when (val result = plainTextExtractor.extractText(request.contentBytes, request.contentStreamProvider, canonicalMime, request.fileName)) {
                    is FileTextExtractor.ExtractionResult.Success -> ExtractionStatus.COMPLETED to result.text
                    is FileTextExtractor.ExtractionResult.Failed -> ExtractionStatus.FAILED to null
                    is FileTextExtractor.ExtractionResult.Unsupported -> ExtractionStatus.UNSUPPORTED to null
                }
            }
            DetectedFileType.PDF -> {
                when (val result = pdfTextExtractor.extractText(request.contentBytes, request.contentStreamProvider, canonicalMime, request.fileName)) {
                    is FileTextExtractor.ExtractionResult.Success -> ExtractionStatus.COMPLETED to result.text
                    is FileTextExtractor.ExtractionResult.Failed -> ExtractionStatus.FAILED to null
                    is FileTextExtractor.ExtractionResult.Unsupported -> ExtractionStatus.UNSUPPORTED to null
                }
            }
            DetectedFileType.DOCX -> {
                when (val result = docxTextExtractor.extractText(request.contentBytes, request.contentStreamProvider, canonicalMime, request.fileName)) {
                    is FileTextExtractor.ExtractionResult.Success -> ExtractionStatus.COMPLETED to result.text
                    is FileTextExtractor.ExtractionResult.Failed -> ExtractionStatus.FAILED to null
                    is FileTextExtractor.ExtractionResult.Unsupported -> ExtractionStatus.UNSUPPORTED to null
                }
            }
            DetectedFileType.IMAGE -> {
                // Images remain EXTRACTION_UNSUPPORTED for now until Vision phase
                ExtractionStatus.UNSUPPORTED to null
            }
            DetectedFileType.UNSUPPORTED -> ExtractionStatus.UNSUPPORTED to null
        }

        val now = timeProvider()
        val document = FileDocument(
            id = idGenerator(),
            fileName = request.fileName.trim(),
            mimeType = canonicalMime,
            fileSize = request.fileSize,
            localUri = request.localUri.trim(),
            createdAt = now,
            updatedAt = now,
            extractionStatus = extractionStatus,
            extractedText = extractedText,
            contentHash = contentHash
        )

        // 7. Privacy & Incognito Isolation
        // Never persist imported files if incognito is enabled.
        if (!request.isIncognito) {
            fileRepository.saveFile(document, isIncognito = false)
        }

        return FileImportResult.Success(
            document = document,
            isDuplicate = false
        )
    }

    private fun validateMetadata(request: FileImportRequest): FileImportResult.InvalidMetadata? {
        val trimmedName = request.fileName.trim()
        if (trimmedName.isBlank()) {
            return FileImportResult.InvalidMetadata(
                fileName = request.fileName,
                reason = "File name must not be blank"
            )
        }

        if (trimmedName.length > MAX_FILE_NAME_LENGTH) {
            return FileImportResult.InvalidMetadata(
                fileName = request.fileName,
                reason = "File name exceeds maximum allowed length of $MAX_FILE_NAME_LENGTH characters"
            )
        }

        if (request.fileSize <= 0L) {
            return FileImportResult.InvalidMetadata(
                fileName = request.fileName,
                reason = "File size must be greater than zero bytes"
            )
        }

        if (request.fileSize > MAX_FILE_SIZE_BYTES) {
            return FileImportResult.InvalidMetadata(
                fileName = request.fileName,
                reason = "File size exceeds maximum limit of 50 MB"
            )
        }

        if (request.localUri.isBlank()) {
            return FileImportResult.InvalidMetadata(
                fileName = request.fileName,
                reason = "Local URI must not be blank"
            )
        }

        return null
    }

    private fun extractHeaderBytes(request: FileImportRequest): ByteArray? {
        if (request.contentBytes != null) {
            return request.contentBytes.take(HEADER_SAMPLE_SIZE).toByteArray()
        }

        if (request.contentStreamProvider != null) {
            return try {
                request.contentStreamProvider.invoke().use { stream ->
                    val buffer = ByteArray(HEADER_SAMPLE_SIZE)
                    val read = stream.read(buffer)
                    if (read > 0) buffer.copyOf(read) else null
                }
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun calculateContentHash(request: FileImportRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")

        if (request.contentBytes != null) {
            val hashBytes = digest.digest(request.contentBytes)
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        if (request.contentStreamProvider != null) {
            try {
                request.contentStreamProvider.invoke().use { stream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                // Fallback to deterministic metadata hash if stream reading fails
            }
        }

        val metadataString = "${request.fileName}:${request.fileSize}:${request.localUri}"
        val hashBytes = digest.digest(metadataString.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_FILE_NAME_LENGTH = 255
        const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB
        private const val HEADER_SAMPLE_SIZE = 32
    }
}
