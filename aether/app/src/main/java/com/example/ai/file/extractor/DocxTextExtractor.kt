package com.example.ai.file.extractor

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts readable paragraph text from DOCX without uploading the document. */
class DocxTextExtractor(
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
                return FileTextExtractor.ExtractionResult.Failed("DOCX content is empty")
            }

            val builder = StringBuilder()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val text = xml
                            .replace(Regex("<w:tab[^>]*/>"), "\t")
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")

                        builder.append(text)
                        break
                    }
                    entry = zip.nextEntry
                }
            }

            val cleaned = builder.toString().replace(Regex("[ \\t]+"), " ").trim()
            val finalText = if (cleaned.length > maxCharacters) cleaned.substring(0, maxCharacters) else cleaned
            FileTextExtractor.ExtractionResult.Success(finalText)
        } catch (e: Exception) {
            FileTextExtractor.ExtractionResult.Failed(
                reason = "Failed to extract DOCX text from $fileName: ${e.message}",
                cause = e
            )
        }
    }
}
