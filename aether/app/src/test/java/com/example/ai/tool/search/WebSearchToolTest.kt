package com.example.ai.tool.search

import com.example.ai.tool.model.ToolCall
import com.example.ai.tool.search.model.WebSearchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebSearchToolTest {

    private lateinit var fakeBackend: FakeWebSearchBackend
    private lateinit var searchTool: WebSearchTool

    @Before
    fun setUp() {
        fakeBackend = FakeWebSearchBackend()
        searchTool = WebSearchTool(fakeBackend)
    }

    @Test
    fun testToolDefinitionProperties() {
        assertEquals("web_search", searchTool.name)
        assertTrue(searchTool.description.isNotBlank())
        assertEquals(2, searchTool.definition.parameters.size)

        val queryParam = searchTool.definition.parameters.find { it.name == "query" }
        assertNotNull(queryParam)
        assertTrue(queryParam!!.isRequired)

        val maxResultsParam = searchTool.definition.parameters.find { it.name == "max_results" }
        assertNotNull(maxResultsParam)
        assertFalse(maxResultsParam!!.isRequired)
    }

    @Test
    fun testBlankQueryValidation() = runBlocking {
        val result = searchTool.execute(mapOf("query" to "   "))
        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("cannot be blank") ?: false)
    }

    @Test
    fun testOversizedQueryValidation() = runBlocking {
        val longQuery = "a".repeat(300)
        val result = searchTool.execute(mapOf("query" to longQuery))
        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("exceeds maximum allowed length") ?: false)
    }

    @Test
    fun testSuccessfulExecutionAndResultMapping() = runBlocking {
        val result = searchTool.execute(mapOf("query" to "Aether Cognitive Workspace", "max_results" to 2))
        assertTrue(result.isSuccess)
        assertFalse(result.isError)

        val payload = JSONArray(result.content)
        assertEquals(2, payload.length())

        val firstResult = payload.getJSONObject(0)
        assertEquals("Aether Cognitive Workspace", firstResult.getString("title"))
        assertEquals("https://aether.workspace.dev", firstResult.getString("url"))
        assertEquals("Aether is an autonomous cognitive workspace assistant built with complete focus, Zero Noise architecture, and privacy in mind.", firstResult.getString("snippet"))
        assertEquals("Aether Dev Team", firstResult.getString("source"))
        assertEquals("2026-09-25", firstResult.getString("published_date"))
    }

    @Test
    fun testUrlSanitizationAndSourcePreservation() = runBlocking {
        fakeBackend.customResults = listOf(
            WebSearchResult(
                title = "Malicious Protocol Page",
                url = "javascript:alert('pwned')",
                snippet = "This contains an unsafe URL schema.",
                source = "Hacker Zone"
            ),
            WebSearchResult(
                title = "Relative Page",
                url = "developer.android.com",
                snippet = "This does not start with http or https.",
                source = "Android"
            )
        )

        val result = searchTool.execute(mapOf("query" to "Security Test"))
        assertTrue(result.isSuccess)

        val payload = JSONArray(result.content)
        assertEquals(2, payload.length())

        val pwnedObj = payload.getJSONObject(0)
        assertEquals("unsafe-protocol-redacted", pwnedObj.getString("url"))
        assertEquals("Hacker Zone", pwnedObj.getString("source"))

        val relativeObj = payload.getJSONObject(1)
        assertEquals("unsafe-protocol-redacted", relativeObj.getString("url"))
    }

    @Test
    fun testResultSizeLimitsAndTruncation() = runBlocking {
        val oversizedTitle = "X".repeat(250)
        val oversizedSnippet = "Y".repeat(1200)

        fakeBackend.customResults = listOf(
            WebSearchResult(
                title = oversizedTitle,
                url = "https://safe.url",
                snippet = oversizedSnippet
            )
        )

        val result = searchTool.execute(mapOf("query" to "Limits Test"))
        assertTrue(result.isSuccess)

        val payload = JSONArray(result.content)
        val resultObj = payload.getJSONObject(0)

        val parsedTitle = resultObj.getString("title")
        val parsedSnippet = resultObj.getString("snippet")

        assertTrue(parsedTitle.length <= WebSearchTool.MAX_TITLE_LENGTH)
        assertTrue(parsedTitle.endsWith("..."))

        assertTrue(parsedSnippet.length <= WebSearchTool.MAX_SNIPPET_LENGTH)
        assertTrue(parsedSnippet.endsWith("..."))
    }

    @Test
    fun testMaxResultsClamping() = runBlocking {
        fakeBackend.customResults = (1..15).map { index ->
            WebSearchResult("Title $index", "https://url-$index.com", "Snippet $index")
        }

        // Test custom low boundary
        val resultLow = searchTool.execute(mapOf("query" to "test", "max_results" to 2))
        assertEquals(2, JSONArray(resultLow.content).length())

        // Test system default mapping when none provided
        val resultDefault = searchTool.execute(mapOf("query" to "test"))
        assertEquals(WebSearchTool.DEFAULT_MAX_RESULTS, JSONArray(resultDefault.content).length())

        // Test exceeding limits does not go beyond absolute limit
        val resultOversized = searchTool.execute(mapOf("query" to "test", "max_results" to 25))
        assertEquals(WebSearchTool.ABSOLUTE_MAX_RESULTS, JSONArray(resultOversized.content).length())
    }

    @Test
    fun testBackendErrorHandling() = runBlocking {
        fakeBackend.shouldFail = true
        fakeBackend.failureMessage = "Rate limit exceeded"

        val result = searchTool.execute(mapOf("query" to "error test"))
        assertTrue(result.isError)
        assertEquals("", result.content)
        assertTrue(result.errorMessage?.contains("Rate limit exceeded") ?: false)
    }

    @Test
    fun testBackendExceptionCaughtSafely() = runBlocking {
        val failingBackend = object : WebSearchBackend {
            override suspend fun search(query: com.example.ai.tool.search.model.WebSearchQuery): com.example.ai.tool.search.model.WebSearchResponse {
                throw IllegalStateException("API network timeout")
            }
        }
        val failingTool = WebSearchTool(failingBackend)

        val result = failingTool.execute(mapOf("query" to "exception test"))
        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("API network timeout") ?: false)
    }

    @Test
    fun testMaliciousResultsArePlainData() = runBlocking {
        val maliciousTitle = "<script>alert('run')</script> Title"
        val maliciousSnippet = "<div class=\"leak\">Leaked text</div>"

        fakeBackend.customResults = listOf(
            WebSearchResult(
                title = maliciousTitle,
                url = "https://safe.com",
                snippet = maliciousSnippet
            )
        )

        val result = searchTool.execute(mapOf("query" to "Injection Test"))
        assertTrue(result.isSuccess)

        val payload = JSONArray(result.content)
        val item = payload.getJSONObject(0)

        // Raw elements must remain unexecuted and just be serialized text fields
        assertEquals(maliciousTitle, item.getString("title"))
        assertEquals(maliciousSnippet, item.getString("snippet"))
    }

    @Test
    fun testSearchIsolatedNoMemoryOrDatabaseMutations() = runBlocking {
        val results = listOf(
            WebSearchResult("A", "https://url.com", "S")
        )
        fakeBackend.customResults = results

        val beforeState = emptyMap<String, Any>()
        val result = searchTool.execute(mapOf("query" to "Isolation Check"))

        assertTrue(result.isSuccess)
        // Ensure no memory states mutated
        assertEquals(beforeState, emptyMap<String, Any>())
    }

    @Test
    fun testSearchExecutionIsDeterministic() = runBlocking {
        val result1 = searchTool.execute(mapOf("query" to "Determinism Test"))
        val result2 = searchTool.execute(mapOf("query" to "Determinism Test"))

        assertEquals(result1.content, result2.content)
        assertEquals(result1.status, result2.status)
    }
}
