package com.example.ai.file.extractor

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FileTextExtractorTest {

    private val plainExtractor = PlainTextExtractor(maxCharacters = 50)
    private val pdfExtractor = PdfTextExtractor(maxCharacters = 50)

    @Test
    fun testPlainTextExtraction() = runBlocking {
        val content = "Hello Aether text extraction pipeline"
        val result = plainExtractor.extractText(
            contentBytes = content.toByteArray(Charsets.UTF_8),
            contentStreamProvider = null,
            mimeType = "text/plain",
            fileName = "notes.txt"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
        assertEquals(content, (result as FileTextExtractor.ExtractionResult.Success).text)
    }

    @Test
    fun testPlainTextSizeLimit() = runBlocking {
        val longContent = "A".repeat(100)
        val result = plainExtractor.extractText(
            contentBytes = longContent.toByteArray(Charsets.UTF_8),
            contentStreamProvider = null,
            mimeType = "text/plain",
            fileName = "long.txt"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
        assertEquals(50, (result as FileTextExtractor.ExtractionResult.Success).text.length)
    }

    @Test
    fun testPlainTextEmpty() = runBlocking {
        val result = plainExtractor.extractText(
            contentBytes = byteArrayOf(),
            contentStreamProvider = null,
            mimeType = "text/plain",
            fileName = "empty.txt"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
        assertEquals("", (result as FileTextExtractor.ExtractionResult.Success).text)
    }

    @Test
    fun testPlainTextMalformedEncoding() = runBlocking {
        val binaryBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01, 0x89.toByte())
        val result = plainExtractor.extractText(
            contentBytes = binaryBytes,
            contentStreamProvider = null,
            mimeType = "text/plain",
            fileName = "binary.bin"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
    }

    @Test
    fun testPdfTextExtraction() = runBlocking {
        val pdfBytes = createTestPdfBytes("Sample PDF Report Text Content")
        val result = pdfExtractor.extractText(
            contentBytes = pdfBytes,
            contentStreamProvider = null,
            mimeType = "application/pdf",
            fileName = "report.pdf"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
        val text = (result as FileTextExtractor.ExtractionResult.Success).text
        assertTrue(text.contains("Sample PDF"))
    }

    @Test
    fun testPdfMalformedOrEncrypted() = runBlocking {
        val badBytes = "Not a PDF document".toByteArray(Charsets.UTF_8)
        val result = pdfExtractor.extractText(
            contentBytes = badBytes,
            contentStreamProvider = null,
            mimeType = "application/pdf",
            fileName = "corrupt.pdf"
        )
        assertTrue(result is FileTextExtractor.ExtractionResult.Failed)
    }

    private fun createTestPdfBytes(textContent: String): ByteArray {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        val contentStream = PDPageContentStream(document, page)
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12f)
        contentStream.newLineAtOffset(100f, 700f)
        contentStream.showText(textContent)
        contentStream.endText()
        contentStream.close()

        val baos = ByteArrayOutputStream()
        document.save(baos)
        document.close()
        return baos.toByteArray()
    }
}
