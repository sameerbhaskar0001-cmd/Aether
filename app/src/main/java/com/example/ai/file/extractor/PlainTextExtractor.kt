package com.example.ai.file.extractor

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class PlainTextExtractor(
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS
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
                return FileTextExtractor.ExtractionResult.Success("")
            }

            // Safe charset decoding with fallbacks
            val decodedText = decodeBytesSafely(bytes)
            
            // Enforce size limit
            val finalTex = if (decodedText.length > maxCharacters) {
                decodedText.substring(0, maxCharacters)
            } else {
                decodedText
            }

            FileTextExtractor.ExtractionResult.Success(finalTex)
        } catch (e: Exception) {
            FileTextExtractor.ExtractionResult.Failed(
                reason = "Failed to extract plain text from $fileName: ${e.message}",
                cause = e
            )
        }
    }

    private fun decodeBytesSafely(bytes: ByteArray): String {
        // 1. Try UTF-8
        try {
            val text = String(bytes, StandardCharsets.UTF_8)
            // Check for severe replacement character corruption if binary data was mistakenly passed as text
            if (!isExcessiveReplacementChars(text)) {
                return text
            }
        } catch (_: Exception) {}

        // 2. Try UTF-16
        try {
            val text = String(bytes, StandardCharsets.UTF_16)
            if (!isExcessiveReplacementChars(text)) {
                return text
            }
        } catch (_: Exception) {}

        // 3. Try ISO-8859-1 (Latin-1) which never fails to decode arbitrary byte sequences
        try {
            return String(bytes, Charset.forName("ISO-8859-1"))
        } catch (_: Exception) {}

        // 4. Ultimate fallback
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun isExcessiveReplacementChars(text: String): Boolean {
        if (text.isEmpty()) return false
        val replacementCount = text.count { it == '\ufffd' }
        // If more than 10% of characters are replacement characters, it's likely binary garbage
        return (replacementCount.toDouble() / text.length) > 0.10
    }

    companion object {
        const val DEFAULT_MAX_CHARACTERS = 1_000_000 // 1 million characters max (~1-2 MB text)
    }
}
