package com.example.ai.file

import java.util.Locale

enum class DetectedFileType {
    PLAIN_TEXT,
    PDF,
    DOCX,
    IMAGE,
    UNSUPPORTED
}

class FileTypeDetector {

    /**
     * Detects file type using magic bytes, MIME type, and file name extension.
     */
    fun detect(
        mimeType: String? = null,
        fileName: String? = null,
        headerBytes: ByteArray? = null
    ): DetectedFileType {
        // 1. Check magic bytes first if available
        if (headerBytes != null && headerBytes.isNotEmpty()) {
            val magicType = detectFromMagicBytes(headerBytes)
            if (magicType != DetectedFileType.UNSUPPORTED) {
                return magicType
            }
        }

        // 2. Check MIME type if provided and not generic
        val cleanMime = mimeType?.trim()?.lowercase(Locale.ROOT)
        if (!cleanMime.isNullOrBlank() && cleanMime != "application/octet-stream" && cleanMime != "binary/octet-stream") {
            val mimeResult = detectFromMimeType(cleanMime)
            if (mimeResult != DetectedFileType.UNSUPPORTED) {
                return mimeResult
            }
        }

        // 3. Fallback to file extension
        if (!fileName.isNullOrBlank()) {
            val extensionResult = detectFromExtension(fileName)
            if (extensionResult != DetectedFileType.UNSUPPORTED) {
                return extensionResult
            }
        }

        // 4. If mimeType is text/plain or similar even without extension
        if (cleanMime != null && (cleanMime.startsWith("text/") || cleanMime == "application/json")) {
            return DetectedFileType.PLAIN_TEXT
        }

        return DetectedFileType.UNSUPPORTED
    }

    fun isSupported(
        mimeType: String? = null,
        fileName: String? = null,
        headerBytes: ByteArray? = null
    ): Boolean {
        return detect(mimeType, fileName, headerBytes) != DetectedFileType.UNSUPPORTED
    }

    /**
     * Resolves a canonical MIME type based on detected type, extension, or original MIME.
     */
    fun resolveCanonicalMimeType(
        detectedType: DetectedFileType,
        originalMime: String?,
        fileName: String?
    ): String {
        val cleanMime = originalMime?.trim()?.lowercase(Locale.ROOT)
        if (!cleanMime.isNullOrBlank() && cleanMime != "application/octet-stream") {
            return cleanMime
        }

        val extension = fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        return when (detectedType) {
            DetectedFileType.PLAIN_TEXT -> when (extension) {
                "json" -> "application/json"
                "md", "markdown" -> "text/markdown"
                "csv" -> "text/csv"
                "tsv" -> "text/tab-separated-values"
                "xml" -> "application/xml"
                "html", "htm" -> "text/html"
                "yaml", "yml" -> "text/yaml"
                else -> "text/plain"
            }
            DetectedFileType.PDF -> "application/pdf"
            DetectedFileType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            DetectedFileType.IMAGE -> when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                else -> "image/jpeg"
            }
            DetectedFileType.UNSUPPORTED -> cleanMime ?: "application/octet-stream"
        }
    }

    private fun detectFromMagicBytes(bytes: ByteArray): DetectedFileType {
        // PDF: starts with %PDF (0x25, 0x50, 0x44, 0x46)
        if (bytes.size >= 4 &&
            bytes[0] == 0x25.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() &&
            bytes[3] == 0x46.toByte()
        ) {
            return DetectedFileType.PDF
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return DetectedFileType.IMAGE
        }

        // JPEG: FF D8 FF
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return DetectedFileType.IMAGE
        }

        // GIF: GIF87a or GIF89a
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
        ) {
            return DetectedFileType.IMAGE
        }

        // BMP: BM (0x42, 0x4D)
        if (bytes.size >= 2 &&
            bytes[0] == 0x42.toByte() &&
            bytes[1] == 0x4D.toByte()
        ) {
            return DetectedFileType.IMAGE
        }

        // WEBP: starts with RIFF and byte 8..11 is WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return DetectedFileType.IMAGE
        }

        return DetectedFileType.UNSUPPORTED
    }

    private fun detectFromMimeType(mime: String): DetectedFileType {
        return when {
            mime == "application/pdf" || mime == "application/x-pdf" -> DetectedFileType.PDF
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DetectedFileType.DOCX
            mime.startsWith("image/") -> DetectedFileType.IMAGE
            mime.startsWith("text/") -> DetectedFileType.PLAIN_TEXT
            mime in TEXT_MIME_TYPES -> DetectedFileType.PLAIN_TEXT
            else -> DetectedFileType.UNSUPPORTED
        }
    }

    private fun detectFromExtension(fileName: String): DetectedFileType {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isBlank()) return DetectedFileType.UNSUPPORTED

        return when (extension) {
            "pdf" -> DetectedFileType.PDF
            "docx" -> DetectedFileType.DOCX
            in IMAGE_EXTENSIONS -> DetectedFileType.IMAGE
            in TEXT_EXTENSIONS -> DetectedFileType.PLAIN_TEXT
            else -> DetectedFileType.UNSUPPORTED
        }
    }

    companion object {
        private val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/javascript",
            "application/typescript",
            "application/x-yaml",
            "text/yaml",
            "text/csv",
            "text/markdown"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "ico", "tiff", "tif"
        )

        private val TEXT_EXTENSIONS = setOf(
            "txt", "text", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm",
            "yaml", "yml", "kt", "kts", "java", "py", "js", "ts", "sql", "sh", "c", "cpp", "h",
            "hpp", "cs", "go", "rs", "rb", "properties", "env", "gradle"
        )
    }
}
