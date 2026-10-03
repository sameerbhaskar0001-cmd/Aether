package com.example.ai.file.extractor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.InputStream

class PdfTextExtractor(
    private val maxCharacters: Int = PlainTextExtractor.DEFAULT_MAX_CHARACTERS
) : FileTextExtractor {

    override suspend fun extractText(
        contentBytes: ByteArray?,
        contentStreamProvider: (() -> InputStream)?,
        mimeType: String,
        fileName: String
    ): FileTextExtractor.ExtractionResult {
        return try {
            val bytes = contentBytes ?: contentStreamProvider?.invoke()?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                return FileTextExtractor.ExtractionResult.Failed("PDF content bytes are empty")
            }

            val document = try {
                PDDocument.load(bytes)
            } catch (e: InvalidPasswordException) {
                return FileTextExtractor.ExtractionResult.Failed("PDF is encrypted and requires a password", e)
            } catch (e: Exception) {
                return FileTextExtractor.ExtractionResult.Failed("Malformed or invalid PDF format: ${e.message}", e)
            }

            document.use { doc ->
                if (doc.isEncrypted) {
                    return FileTextExtractor.ExtractionResult.Failed("PDF is encrypted")
                }

                val stripper = PDFTextStripper().apply {
                    setSortByPosition(true)
                }

                val rawText = stripper.getText(doc) ?: ""
                val trimmed = rawText.trim()

                if (trimmed.isEmpty()) {
                    return FileTextExtractor.ExtractionResult.Success("")
                }

                val finalText = if (trimmed.length > maxCharacters) {
                    trimmed.substring(0, maxCharacters)
                } else {
                    trimmed
                }

                FileTextExtractor.ExtractionResult.Success(finalText)
            }
        } catch (e: Exception) {
            FileTextExtractor.ExtractionResult.Failed(
                reason = "Failed to extract text from PDF $fileName: ${e.message}",
                cause = e
            )
        }
    }
}
