package com.example.ai.file.search

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class FileContextBuilderTest {
    @Test
    fun emptyResultsReturnNull() {
        assertNull(FileContextBuilder.build(emptyList()))
    }

    @Test
    fun contextContainsFileNameAndSnippet() {
        val context = FileContextBuilder.build(
            listOf(
                FileSearchResult("1", "roadmap.pdf", 1.5f, "Memory architecture section", "application/pdf")
            )
        )
        requireNotNull(context)
        assertTrue(context.contains("roadmap.pdf"))
        assertTrue(context.contains("Memory architecture section"))
    }

    @Test
    fun contextIsBounded() {
        val results = (1..20).map {
            FileSearchResult(it.toString(), "file$it.txt", 1f, "x".repeat(2000), "text/plain")
        }
        val context = FileContextBuilder.build(results)
        requireNotNull(context)
        assertTrue(context.length <= FileContextBuilder.MAX_TOTAL_CHARS)
    }

    @Test
    fun fileNameNewlinesAreSanitized() {
        val context = FileContextBuilder.build(
            listOf(FileSearchResult("1", "bad\nname.txt", 1f, "snippet", "text/plain"))
        )
        requireNotNull(context)
        assertTrue(!context.contains("bad\nname.txt"))
        assertTrue(context.contains("bad name.txt"))
    }
}
