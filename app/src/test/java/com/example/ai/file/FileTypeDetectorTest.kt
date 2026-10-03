package com.example.ai.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileTypeDetectorTest {

    private lateinit var detector: FileTypeDetector

    @Before
    fun setUp() {
        detector = FileTypeDetector()
    }

    @Test
    fun testPlainTextDetectionByMimeType() {
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = "text/plain", fileName = "test.txt")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = "text/markdown", fileName = "notes.md")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = "text/csv", fileName = "data.csv")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = "application/json", fileName = "payload.json")
        )
    }

    @Test
    fun testPlainTextDetectionByExtension() {
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = null, fileName = "document.txt")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = null, fileName = "README.md")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = null, fileName = "code.kt")
        )
        assertEquals(
            DetectedFileType.PLAIN_TEXT,
            detector.detect(mimeType = null, fileName = "config.yaml")
        )
    }

    @Test
    fun testPdfDetectionByMimeAndExtension() {
        assertEquals(
            DetectedFileType.PDF,
            detector.detect(mimeType = "application/pdf", fileName = "sample.pdf")
        )
        assertEquals(
            DetectedFileType.PDF,
            detector.detect(mimeType = null, fileName = "document.pdf")
        )
    }

    @Test
    fun testPdfDetectionByMagicBytes() {
        val pdfMagic = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x35) // %PDF-1.5
        assertEquals(
            DetectedFileType.PDF,
            detector.detect(mimeType = "application/octet-stream", fileName = "unknown_blob", headerBytes = pdfMagic)
        )
    }

    @Test
    fun testImageDetectionByMimeAndExtension() {
        assertEquals(
            DetectedFileType.IMAGE,
            detector.detect(mimeType = "image/png", fileName = "photo.png")
        )
        assertEquals(
            DetectedFileType.IMAGE,
            detector.detect(mimeType = "image/jpeg", fileName = "avatar.jpg")
        )
        assertEquals(
            DetectedFileType.IMAGE,
            detector.detect(mimeType = null, fileName = "graphic.webp")
        )
    }

    @Test
    fun testImageDetectionByMagicBytes() {
        // PNG magic bytes
        val pngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        assertEquals(
            DetectedFileType.IMAGE,
            detector.detect(mimeType = null, fileName = "file.bin", headerBytes = pngMagic)
        )

        // JPEG magic bytes
        val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(
            DetectedFileType.IMAGE,
            detector.detect(mimeType = null, fileName = "photo", headerBytes = jpegMagic)
        )
    }

    @Test
    fun testUnsupportedFilesHandling() {
        assertEquals(
            DetectedFileType.UNSUPPORTED,
            detector.detect(mimeType = "application/x-msdownload", fileName = "setup.exe")
        )
        assertEquals(
            DetectedFileType.UNSUPPORTED,
            detector.detect(mimeType = "application/zip", fileName = "archive.zip")
        )
        assertEquals(
            DetectedFileType.UNSUPPORTED,
            detector.detect(mimeType = "audio/mpeg", fileName = "song.mp3")
        )
        assertEquals(
            DetectedFileType.UNSUPPORTED,
            detector.detect(mimeType = "video/mp4", fileName = "clip.mp4")
        )
        assertEquals(
            DetectedFileType.UNSUPPORTED,
            detector.detect(mimeType = null, fileName = "unknown_file")
        )

        assertFalse(detector.isSupported(fileName = "setup.exe"))
        assertTrue(detector.isSupported(fileName = "document.pdf"))
        assertTrue(detector.isSupported(fileName = "notes.txt"))
    }
    @Test
    fun testDocxDetectionByExtensionAndMime() {
        assertEquals(DetectedFileType.DOCX, detector.detect(fileName = "notes.docx"))
        assertEquals(DetectedFileType.DOCX, detector.detect(mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }

}
