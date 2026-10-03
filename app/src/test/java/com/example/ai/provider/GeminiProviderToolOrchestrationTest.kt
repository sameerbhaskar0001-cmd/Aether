package com.example.ai.provider

import com.example.ai.SystemTimeProvider
import com.example.ai.model.Candidate
import com.example.ai.model.Content
import com.example.ai.model.GenerateContentRequest
import com.example.ai.model.GenerateContentResponse
import com.example.ai.model.Part
import com.example.ai.model.GeminiFunctionCall
import com.example.ai.model.GeminiFunctionResponse
import com.example.ai.network.GeminiApiService
import com.example.ai.provider.models.ProviderRequest
import com.example.ai.tool.Tool
import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.impl.EchoTool
import com.example.ai.tool.orchestrator.ToolOrchestrator
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiProviderToolOrchestrationTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor
    private lateinit var orchestrator: ToolOrchestrator
    private lateinit var fakeApiService: FakeGeminiApiService
    private lateinit var provider: GeminiProvider

    open class FakeGeminiApiService : GeminiApiService {
        var responseToReturn: GenerateContentResponse? = null
        var lastRequest: GenerateContentRequest? = null
        var allRequests = mutableListOf<GenerateContentRequest>()
        var streamPayload: String = ""

        override suspend fun generateContent(
            apiKey: String,
            request: GenerateContentRequest
        ): GenerateContentResponse {
            lastRequest = request
            allRequests.add(request)
            return responseToReturn ?: GenerateContentResponse()
        }

        override suspend fun generateContentStream(
            apiKey: String,
            request: GenerateContentRequest
        ): ResponseBody {
            lastRequest = request
            allRequests.add(request)
            return streamPayload.toResponseBody("text/event-stream".toMediaType())
        }
    }

    @Before
    fun setUp() {
        registry = ToolRegistry()
        executor = ToolExecutor(registry)
        orchestrator = ToolOrchestrator(executor)
        fakeApiService = FakeGeminiApiService()
        provider = GeminiProvider(
            apiKeyProvider = { "fake_key_for_test" },
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            apiService = fakeApiService
        )
    }

    @Test
    fun testNormalGeminiChatWithoutToolsStillWorks() = runBlocking {
        // Prepare simple text response
        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(text = "Hello! how can I assist you today?"))))
            )
        )

        val request = ProviderRequest(userMessage = "Hi")
        val response = provider.generate(request)

        assertEquals("Hello! how can I assist you today?", response.text)
        assertEquals("Gemini", response.providerName)
        assertNull(fakeApiService.lastRequest?.tools) // No tools config if none registered
    }

    @Test
    fun testGeminiToolDefinitionSerialization() = runBlocking {
        // Register an echo tool
        registry.register(EchoTool())

        fakeApiService.responseToReturn = GenerateContentResponse(
            candidates = listOf(Candidate(content = Content(parts = listOf(Part(text = "Response")))))
        )

        val request = ProviderRequest(userMessage = "use echo")
        provider.generate(request)

        val lastReq = fakeApiService.lastRequest
        assertNotNull(lastReq)
        assertNotNull(lastReq?.tools)
        assertEquals(1, lastReq?.tools?.size)

        val toolConfig = lastReq?.tools?.first()
        assertNotNull(toolConfig?.functionDeclarations)
        assertEquals(1, toolConfig?.functionDeclarations?.size)

        val function = toolConfig?.functionDeclarations?.first()
        assertEquals("echo", function?.name)
        assertEquals("Echoes the provided text message back as the tool execution result.", function?.description)
        assertEquals("OBJECT", function?.parameters?.type)
        assertTrue(function?.parameters?.properties?.containsKey("text") ?: false)
    }

    @Test
    fun testGeminiToolCallParsingAndSuccessfulExecution() = runBlocking {
        // Register a fake web_search tool
        val fakeWebSearch = object : Tool {
            override val name: String = "web_search"
            override val description: String = "Perform web search"
            override val definition: ToolDefinition = ToolDefinition(
                name = "web_search",
                description = "Perform web search",
                parameters = listOf(
                    ToolParameter(name = "query", type = ToolParameterType.STRING, description = "search query")
                )
            )

            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                val query = arguments["query"] as? String ?: ""
                return ToolResult.success("web_search", "Results for '$query': Aether is excellent.")
            }
        }
        registry.register(fakeWebSearch)

        // 1. First turn returns a tool/function call request
        val functionCall = GeminiFunctionCall(name = "web_search", args = mapOf("query" to "Aether project"))
        val firstTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(functionCall = functionCall))))
            )
        )

        // 2. Second turn returns the final text response
        val secondTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(text = "Aether is a cognitive workspace assistant."))))
            )
        )

        // Setup the mock to return first turn initially, then transition
        var apiCallCount = 0
        fakeApiService = object : FakeGeminiApiService() {
            override suspend fun generateContent(
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                lastRequest = request
                allRequests.add(request)
                apiCallCount++
                return if (apiCallCount == 1) firstTurnResponse else secondTurnResponse
            }
        }

        provider = GeminiProvider(
            apiKeyProvider = { "fake_key" },
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            apiService = fakeApiService
        )

        val request = ProviderRequest(userMessage = "Search for Aether")
        val response = provider.generate(request)

        assertEquals("Aether is a cognitive workspace assistant.", response.text)
        assertEquals(2, apiCallCount) // First turn requesting function + Second turn final completion

        // Ensure proper sequence is sent back to Gemini in turn 2
        val finalRequest = fakeApiService.lastRequest
        assertNotNull(finalRequest)
        assertEquals(3, finalRequest?.contents?.size) // User message, Model request, Function response

        val turn1 = finalRequest?.contents?.get(0)
        assertEquals("user", turn1?.role)
        assertEquals("Search for Aether", turn1?.parts?.get(0)?.text)

        val turn2 = finalRequest?.contents?.get(1)
        assertEquals("model", turn2?.role)
        assertEquals("web_search", turn2?.parts?.get(0)?.functionCall?.name)

        val turn3 = finalRequest?.contents?.get(2)
        assertEquals("function", turn3?.role)
        val responsePart = turn3?.parts?.get(0)?.functionResponse
        assertEquals("web_search", responsePart?.name)
        assertEquals("SUCCESS", responsePart?.response?.get("status"))
        assertEquals("Results for 'Aether project': Aether is excellent.", responsePart?.response?.get("content"))
    }

    @Test
    fun testUnknownToolRejection() = runBlocking {
        // Trigger model requesting unknown_tool
        val functionCall = GeminiFunctionCall(name = "unknown_tool", args = mapOf("arg" to "val"))
        val firstTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(functionCall = functionCall))))
            )
        )

        val secondTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(text = "Handled unknown tool failure."))))
            )
        )

        var apiCallCount = 0
        fakeApiService = object : FakeGeminiApiService() {
            override suspend fun generateContent(
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                lastRequest = request
                allRequests.add(request)
                apiCallCount++
                return if (apiCallCount == 1) firstTurnResponse else secondTurnResponse
            }
        }

        provider = GeminiProvider(
            apiKeyProvider = { "fake_key" },
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            apiService = fakeApiService
        )

        val request = ProviderRequest(userMessage = "execute custom tool")
        val response = provider.generate(request)

        assertEquals("Handled unknown tool failure.", response.text)
        assertEquals(2, apiCallCount)

        val finalRequest = fakeApiService.lastRequest
        val functionResponseTurn = finalRequest?.contents?.get(2)
        assertEquals("function", functionResponseTurn?.role)
        val responsePart = functionResponseTurn?.parts?.get(0)?.functionResponse
        assertEquals("unknown_tool", responsePart?.name)
        assertEquals("ERROR", responsePart?.response?.get("status"))
        assertTrue(responsePart?.response?.get("errorMessage")?.toString()?.contains("not registered") ?: false)
    }

    @Test
    fun testInvalidToolArguments() = runBlocking {
        registry.register(EchoTool()) // Requires 'text' parameter

        // Model requests with invalid or missing parameter
        val functionCall = GeminiFunctionCall(name = "echo", args = emptyMap())
        val firstTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(functionCall = functionCall))))
            )
        )

        val secondTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(text = "Dealt with missing argument error."))))
            )
        )

        var apiCallCount = 0
        fakeApiService = object : FakeGeminiApiService() {
            override suspend fun generateContent(
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                lastRequest = request
                allRequests.add(request)
                apiCallCount++
                return if (apiCallCount == 1) firstTurnResponse else secondTurnResponse
            }
        }

        provider = GeminiProvider(
            apiKeyProvider = { "fake_key" },
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            apiService = fakeApiService
        )

        val request = ProviderRequest(userMessage = "run echo empty")
        val response = provider.generate(request)

        assertEquals("Dealt with missing argument error.", response.text)
        assertEquals(2, apiCallCount)

        val finalRequest = fakeApiService.lastRequest
        val functionResponseTurn = finalRequest?.contents?.get(2)
        assertEquals("function", functionResponseTurn?.role)
        val responsePart = functionResponseTurn?.parts?.get(0)?.functionResponse
        assertEquals("echo", responsePart?.name)
        assertEquals("ERROR", responsePart?.response?.get("status"))
        assertTrue(responsePart?.response?.get("errorMessage")?.toString()?.contains("Missing required parameter") ?: false)
    }

    @Test
    fun testGeminiIncognitoInjectedToToolCall() = runBlocking {
        var isIncognitoReceived = false

        val testIncogTool = object : Tool {
            override val name: String = "test_incog_tool"
            override val description: String = "Asserts incognito param is present"
            override val definition: ToolDefinition = ToolDefinition(
                name = "test_incog_tool",
                description = "Asserts incognito",
                parameters = emptyList()
            )

            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                isIncognitoReceived = arguments["is_incognito"] as? Boolean ?: false
                return ToolResult.success("test_incog_tool", "Incog Asserted")
            }
        }
        registry.register(testIncogTool)

        val functionCall = GeminiFunctionCall(name = "test_incog_tool", args = emptyMap())
        val firstTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(functionCall = functionCall))))
            )
        )

        val secondTurnResponse = GenerateContentResponse(
            candidates = listOf(
                Candidate(content = Content(parts = listOf(Part(text = "Final incognito result received."))))
            )
        )

        var apiCallCount = 0
        fakeApiService = object : FakeGeminiApiService() {
            override suspend fun generateContent(
                apiKey: String,
                request: GenerateContentRequest
            ): GenerateContentResponse {
                lastRequest = request
                allRequests.add(request)
                apiCallCount++
                return if (apiCallCount == 1) firstTurnResponse else secondTurnResponse
            }
        }

        provider = GeminiProvider(
            apiKeyProvider = { "fake_key" },
            toolRegistry = registry,
            toolOrchestrator = orchestrator,
            apiService = fakeApiService
        )

        val request = ProviderRequest(userMessage = "test privacy", isIncognito = true)
        val response = provider.generate(request)

        assertEquals("Final incognito result received.", response.text)
        assertTrue(isIncognitoReceived)
    }
}
