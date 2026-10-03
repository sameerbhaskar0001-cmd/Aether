package com.example.ai.file.extractor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocxTextExtractorTest {
    @Test
    fun extractsDocumentXmlText() = runBlocking {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>Hello Aether</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }

        val result = DocxTextExtractor().extractText(output.toByteArray(), null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "test.docx")
        assertTrue(result is FileTextExtractor.ExtractionResult.Success)
        assertTrue((result as FileTextExtractor.ExtractionResult.Success).text.contains("Hello Aether"))
    }
}
