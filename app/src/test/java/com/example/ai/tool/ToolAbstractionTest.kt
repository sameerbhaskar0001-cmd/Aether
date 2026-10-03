package com.example.ai.tool

import com.example.ai.tool.impl.EchoTool
import com.example.ai.tool.model.ToolCall
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.ai.tool.model.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolAbstractionTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        registry = ToolRegistry()
        executor = ToolExecutor(registry)
    }

    @Test
    fun testToolRegistrationAndLookup() {
        val echoTool = EchoTool()
        val registered = registry.register(echoTool)

        assertTrue(registered)
        assertEquals(1, registry.size)
        assertTrue(registry.contains("echo"))

        val retrieved = registry.get("echo")
        assertNotNull(retrieved)
        assertEquals("echo", retrieved?.name)
        assertEquals(echoTool.description, retrieved?.description)
    }

    @Test
    fun testDuplicateToolRegistrationRejected() {
        val tool1 = EchoTool()
        val tool2 = object : Tool {
            override val name: String = "echo"
            override val description: String = "Duplicate echo"
            override val definition: ToolDefinition = ToolDefinition("echo", "Duplicate")
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                return ToolResult.success("echo", "dup")
            }
        }

        assertTrue(registry.register(tool1))
        val secondAttempt = registry.register(tool2)

        assertFalse("Duplicate tool registration must be rejected", secondAttempt)
        assertEquals(1, registry.size)
        assertEquals(tool1.description, registry.get("echo")?.description)
    }

    @Test
    fun testBlankToolRegistrationRejected() {
        val blankTool = object : Tool {
            override val name: String = "   "
            override val description: String = "Blank"
            override val definition: ToolDefinition = ToolDefinition("", "Blank")
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult = ToolResult.success("", "")
        }

        assertFalse(registry.register(blankTool))
        assertEquals(0, registry.size)
    }

    @Test
    fun testToolListingAndDefinitions() {
        val echoTool = EchoTool()
        val customTool = object : Tool {
            override val name: String = "calculator"
            override val description: String = "Performs calculations"
            override val definition: ToolDefinition = ToolDefinition(
                name = name,
                description = description,
                parameters = listOf(
                    ToolParameter("expression", ToolParameterType.STRING, "Math expression", true)
                )
            )
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                return ToolResult.success(name, "42")
            }
        }

        registry.register(echoTool)
        registry.register(customTool)

        val tools = registry.getAll()
        assertEquals(2, tools.size)
        assertEquals("echo", tools[0].name)
        assertEquals("calculator", tools[1].name)

        val definitions = registry.getDefinitions()
        assertEquals(2, definitions.size)
        assertEquals("echo", definitions[0].name)
        assertEquals("calculator", definitions[1].name)
    }

    @Test
    fun testUnregisterAndClear() {
        val echoTool = EchoTool()
        registry.register(echoTool)
        assertEquals(1, registry.size)

        val removed = registry.unregister("echo")
        assertTrue(removed)
        assertEquals(0, registry.size)
        assertNull(registry.get("echo"))

        registry.register(echoTool)
        registry.clear()
        assertEquals(0, registry.size)
    }

    @Test
    fun testEchoToolSuccessfulExecution() = runBlocking {
        registry.register(EchoTool())

        val toolCall = ToolCall(
            toolName = "echo",
            arguments = mapOf("text" to "Hello Aether Cognitive Workspace"),
            callId = "call-123"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isSuccess)
        assertFalse(result.isError)
        assertEquals("echo", result.toolName)
        assertEquals("Hello Aether Cognitive Workspace", result.content)
        assertEquals("call-123", result.callId)
        assertNull(result.errorMessage)
    }

    @Test
    fun testUnknownToolHandlingReturnsControlledError() = runBlocking {
        val toolCall = ToolCall(
            toolName = "non_existent_tool",
            arguments = mapOf("param" to "val"),
            callId = "call-999"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertFalse(result.isSuccess)
        assertEquals("non_existent_tool", result.toolName)
        assertEquals("call-999", result.callId)
        assertEquals("", result.content)
        assertTrue(result.errorMessage?.contains("not registered") ?: false)
    }

    @Test
    fun testBlankToolCallReturnsControlledError() = runBlocking {
        val toolCall = ToolCall(toolName = "  ", callId = "call-blank")
        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertEquals("call-blank", result.callId)
        assertTrue(result.errorMessage?.contains("cannot be blank") ?: false)
    }

    @Test
    fun testMissingRequiredParameterReturnsControlledError() = runBlocking {
        registry.register(EchoTool())

        val toolCall = ToolCall(
            toolName = "echo",
            arguments = emptyMap(),
            callId = "call-missing"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertEquals("echo", result.toolName)
        assertEquals("call-missing", result.callId)
        assertTrue(result.errorMessage?.contains("Missing required parameter 'text'") ?: false)
    }

    @Test
    fun testBlankStringForRequiredParameterReturnsControlledError() = runBlocking {
        registry.register(EchoTool())

        val toolCall = ToolCall(
            toolName = "echo",
            arguments = mapOf("text" to "   "),
            callId = "call-blank-param"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("Missing required parameter 'text'") ?: false)
    }

    @Test
    fun testParameterTypeMismatchReturnsControlledError() = runBlocking {
        val arrayTool = object : Tool {
            override val name: String = "array_tool"
            override val description: String = "Requires list"
            override val definition: ToolDefinition = ToolDefinition(
                name = name,
                description = description,
                parameters = listOf(
                    ToolParameter("items", ToolParameterType.ARRAY, "List of items", true)
                )
            )
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                return ToolResult.success(name, "ok")
            }
        }

        registry.register(arrayTool)

        val toolCall = ToolCall(
            toolName = "array_tool",
            arguments = mapOf("items" to "not_an_array"),
            callId = "call-type-err"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertTrue(result.errorMessage?.contains("Invalid type for parameter 'items'") ?: false)
    }

    @Test
    fun testExtraUnknownArgumentsAreTolerated() = runBlocking {
        registry.register(EchoTool())

        val toolCall = ToolCall(
            toolName = "echo",
            arguments = mapOf(
                "text" to "Valid payload",
                "unknown_extra_field" to "extra_data",
                "future_token" to 12345
            ),
            callId = "call-extra-fields"
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isSuccess)
        assertEquals("Valid payload", result.content)
    }

    @Test
    fun testToolThrowingExceptionIsSafelyCaughtAndReturnsError() = runBlocking {
        val failingTool = object : Tool {
            override val name: String = "failing_tool"
            override val description: String = "Fails at runtime"
            override val definition: ToolDefinition = ToolDefinition(name, description)
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                throw IllegalStateException("Fatal internal computation failure")
            }
        }

        registry.register(failingTool)

        val toolCall = ToolCall(toolName = "failing_tool", callId = "call-fail")
        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertEquals("failing_tool", result.toolName)
        assertEquals("call-fail", result.callId)
        assertTrue(result.errorMessage?.contains("Execution of tool 'failing_tool' failed") ?: false)
        assertTrue(result.errorMessage?.contains("Fatal internal computation failure") ?: false)
    }

    @Test
    fun testToolExceptionRedactsSensitiveSecretsAndTokens() = runBlocking {
        val leakingTool = object : Tool {
            override val name: String = "leaking_tool"
            override val description: String = "Accidentally leaks credentials in exception"
            override val definition: ToolDefinition = ToolDefinition(name, description)
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                throw RuntimeException("HTTP failure with Bearer secret_bearer_token_12345 and key=AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456 and gsk_groqsecretkey99999")
            }
        }

        registry.register(leakingTool)

        val toolCall = ToolCall(toolName = "leaking_tool")
        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        val errorMsg = result.errorMessage ?: ""
        assertFalse(errorMsg.contains("secret_bearer_token_12345"))
        assertFalse(errorMsg.contains("AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456"))
        assertFalse(errorMsg.contains("gsk_groqsecretkey99999"))
        assertTrue(errorMsg.contains("Bearer REDACTED"))
        assertTrue(errorMsg.contains("REDACTED"))
    }

    @Test
    fun testStandardizedToolResultModelProperties() {
        val successResult = ToolResult.success(
            toolName = "echo",
            content = "Hello",
            callId = "c1",
            metadata = mapOf("executionTimeMs" to 5L)
        )
        assertTrue(successResult.isSuccess)
        assertFalse(successResult.isError)
        assertEquals(ToolStatus.SUCCESS, successResult.status)
        assertEquals("echo", successResult.toolName)
        assertEquals("Hello", successResult.content)
        assertEquals("c1", successResult.callId)
        assertEquals(5L, successResult.metadata["executionTimeMs"])
        assertNull(successResult.errorMessage)

        val errorResult = ToolResult.error(
            toolName = "echo",
            errorMessage = "Failed execution",
            callId = "c2"
        )
        assertTrue(errorResult.isError)
        assertFalse(errorResult.isSuccess)
        assertEquals(ToolStatus.ERROR, errorResult.status)
        assertEquals("echo", errorResult.toolName)
        assertEquals("", errorResult.content)
        assertEquals("c2", errorResult.callId)
        assertEquals("Failed execution", errorResult.errorMessage)
    }

    @Test
    fun testDeterministicOrderPreservedInRegistry() {
        val names = listOf("alpha", "beta", "gamma", "delta", "epsilon")
        names.forEach { name ->
            registry.register(object : Tool {
                override val name: String = name
                override val description: String = "Tool $name"
                override val definition: ToolDefinition = ToolDefinition(name, "Desc")
                override suspend fun execute(arguments: Map<String, Any?>): ToolResult = ToolResult.success(name, "ok")
            })
        }

        val registeredNames = registry.getAll().map { it.name }
        assertEquals(names, registeredNames)
    }
}
