package com.example.ai.tool.search.gemini

import com.example.ai.model.Candidate
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.GenerateContentResponse
import com.example.ai.model.GroundingChunk
import com.example.ai.model.GroundingMetadata
import com.example.ai.model.Part
import com.example.ai.model.WebSource
import com.example.ai.network.GeminiApiService
import com.example.ai.tool.search.model.WebSearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiWebSearchBackendTest {

    private lateinit var fakeApiService: FakeGeminiApiService
    private lateinit var backend: GeminiWebSearchBackend

    private class FakeGeminiApiService : GeminiApiService {
        var responseToReturn: GenerateContentResponse? = null
        var exceptionToThrow: Exception? = null
        var lastApiKeyPassed: String? = null
        var lastRequestPassed: GenerateContentRequest? = null

        override suspend fun generateContent(
            apiKey: String,
            request: GenerateContentRequest
        ): GenerateContentResponse {
            lastApiKeyPassed = apiKey
            lastRequestPassed = request
            exceptionToThrow?.let { throw it }
            return responseToReturn ?: GenerateContentResponse()
        }

        override suspend fun generateContentStream(
            apiKey: String,
            request: GenerateContentRequest
        ): ResponseBody {
            throw UnsupportedOperationException("Streaming not used for search backend")
        }
    }

    @Before
    fun setUp() {
        fakeApiService = FakeGeminiApiService()
        backend = GeminiWebSearchBackend(
            apiService = fakeApiService,
            apiKeyProvider = { "valid_fake_api_key" }
        )
    }

    @Test
    fun testSuccessfulGroundedSearchMapping() = runBlocking {
        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(parts = listOf(Part(text = "Android 15 is the latest release"))),
                    groundingMetadata = GroundingMetadata(
                        webSearchQueries = listOf("Android 15 release notes"),
                        groundingChunks = listOf(
                            GroundingChunk(
                                web = WebSource(
                                    uri = "https://developer.android.com/about/versions/15",
                                    title = "Android 15 Features"
                                )
                            )
                        )
                    )
                )
            )
        )

        val response = backend.search(WebSearchQuery("Android 15"))

        assertTrue(response.isSuccess)
        assertEquals(1, response.results.size)
        val result = response.results[0]
        assertEquals("Android 15 Features", result.title)
        assertEquals("https://developer.android.com/about/versions/15", result.url)
        assertEquals("Android 15 is the latest release", result.snippet)
        assertEquals("developer.android.com", result.source)
    }

    @Test
    fun testMultipleSourcesExtraction() = runBlocking {
        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(parts = listOf(Part(text = "Multi source research response"))),
                    groundingMetadata = GroundingMetadata(
                        groundingChunks = listOf(
                            GroundingChunk(web = WebSource(uri = "https://source1.org/doc", title = "Source One")),
                            GroundingChunk(web = WebSource(uri = "https://source2.com/article", title = "Source Two")),
                            GroundingChunk(web = WebSource(uri = "https://source3.net/info", title = "Source Three"))
                        )
                    )
                )
            )
        )

        val response = backend.search(WebSearchQuery(query = "Multiple sources", maxResults = 2))

        assertTrue(response.isSuccess)
        assertEquals(2, response.results.size)
        assertEquals("Source One", response.results[0].title)
        assertEquals("Source Two", response.results[1].title)
    }

    @Test
    fun testEmptyResultsHandling() = runBlocking {
        fakeApiService.responseToReturn = GenerateContentResponse(candidates = emptyList())

        val response = backend.search(WebSearchQuery("Obscure query"))

        assertTrue(response.isSuccess)
        assertTrue(response.results.isEmpty())
    }

    @Test
    fun testMalformedOrMissingSourceMetadata() = runBlocking {
        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(parts = listOf(Part(text = "Summary without grounding metadata"))),
                    groundingMetadata = null
                )
            )
        )

        val response = backend.search(WebSearchQuery("Missing metadata"))

        assertTrue(response.isSuccess)
        assertEquals(1, response.results.size)
        assertEquals("Google Search Summary", response.results[0].title)
        assertEquals("Summary without grounding metadata", response.results[0].snippet)
        assertTrue(response.results[0].url.contains("google.com/search"))
    }

    @Test
    fun testAuthenticationFailureHandling() = runBlocking {
        val missingKeyBackend = GeminiWebSearchBackend(
            apiService = fakeApiService,
            apiKeyProvider = { "" }
        )

        val response = missingKeyBackend.search(WebSearchQuery("Test"))

        assertFalse(response.isSuccess)
        assertTrue(response.errorMessage?.contains("API key is not configured") ?: false)
    }

    @Test
    fun testHttp401AuthenticationError() = runBlocking {
        val errorResponseBody = "{\"error\": {\"message\": \"API key invalid: AIzaSySecretKey123\"}}".toResponseBody("application/json".toMediaType())
        fakeApiService.exceptionToThrow = HttpException(Response.error<GenerateContentResponse>(401, errorResponseBody))

        val response = backend.search(WebSearchQuery("Auth Fail"))

        assertFalse(response.isSuccess)
        assertTrue(response.errorMessage?.contains("Authentication failed") ?: false)
        assertFalse(response.errorMessage?.contains("AIzaSySecretKey123") ?: true)
    }

    @Test
    fun testRateLimit429ErrorHandling() = runBlocking {
        val errorResponseBody = "{\"error\": {\"message\": \"Resource quota exceeded\"}}".toResponseBody("application/json".toMediaType())
        fakeApiService.exceptionToThrow = HttpException(Response.error<GenerateContentResponse>(429, errorResponseBody))

        val response = backend.search(WebSearchQuery("Rate Limit"))

        assertFalse(response.isSuccess)
        assertTrue(response.errorMessage?.contains("rate limit exceeded") ?: false)
    }

    @Test
    fun testNetworkExceptionHandling() = runBlocking {
        fakeApiService.exceptionToThrow = IOException("Connection reset by peer AIzaSy0123456789abcdefghijklmnopqrstuvw")

        val response = backend.search(WebSearchQuery("Network Fail"))

        assertFalse(response.isSuccess)
        assertTrue(response.errorMessage?.contains("Network error") ?: false)
        assertFalse(response.errorMessage?.contains("AIzaSy0123456789abcdefghijklmnopqrstuvw") ?: true)
    }

    @Test
    fun testResultAndQueryLimitsRespected() = runBlocking {
        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(parts = listOf(Part(text = "Summary"))),
                    groundingMetadata = GroundingMetadata(
                        groundingChunks = (1..10).map { i ->
                            GroundingChunk(web = WebSource(uri = "https://site$i.com", title = "Site $i"))
                        }
                    )
                )
            )
        )

        val response = backend.search(WebSearchQuery(query = "Limit check", maxResults = 3))

        assertTrue(response.isSuccess)
        assertEquals(3, response.results.size)
    }

    @Test
    fun testSecretRedactionInErrorMessage() = runBlocking {
        fakeApiService.exceptionToThrow = RuntimeException("Failed with secret token gsk_1234567890abcdef and AIzaSy0123456789abcdefghijklmnopqrstuvw")

        val response = backend.search(WebSearchQuery("Secret Test"))

        assertFalse(response.isSuccess)
        assertNotNull(response.errorMessage)
        assertFalse(response.errorMessage?.contains("gsk_1234567890abcdef") ?: true)
        assertFalse(response.errorMessage?.contains("AIzaSy0123456789abcdefghijklmnopqrstuvw") ?: true)
        assertTrue(response.errorMessage?.contains("REDACTED_API_KEY") ?: false || response.errorMessage?.contains("Gemini search failed") ?: false)
    }
}
