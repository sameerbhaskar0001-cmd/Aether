package com.example.ai.tool.orchestrator

import com.example.ai.tool.Tool
import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.ToolRegistry
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
class ToolOrchestratorTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor
    private lateinit var orchestrator: ToolOrchestrator

    @Before
    fun setUp() {
        registry = ToolRegistry()
        executor = ToolExecutor(registry)
        orchestrator = ToolOrchestrator(executor)
    }

    @Test
    fun testSuccessfulToolExecution() = runBlocking {
        registry.register(EchoTool())

        val toolCall = ToolCall(
            toolName = "echo",
            arguments = mapOf("text" to "Hello Orchestrator!"),
            callId = "call-101"
        )

        val result = orchestrator.execute(toolCall)

        assertEquals("echo", result.toolName)
        assertEquals(ToolOrchestrationStatus.SUCCESS, result.status)
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals("call-101", result.callId)
        assertEquals("Hello Orchestrator!", result.result.content)
        assertEquals(ToolStatus.SUCCESS, result.result.status)
        assertNull(result.result.errorMessage)
    }

    @Test
    fun testUnknownToolExecution() = runBlocking {
        val toolCall = ToolCall(
            toolName = "unknown_custom_tool",
            arguments = mapOf("param" to "val"),
            callId = "call-999"
        )

        val result = orchestrator.execute(toolCall)

        assertEquals("unknown_custom_tool", result.toolName)
        assertEquals(ToolOrchestrationStatus.FAILURE, result.status)
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertEquals("call-999", result.callId)
        assertNotNull(result.result.errorMessage)
        assertTrue(result.result.errorMessage?.contains("not registered") ?: false)
    }

    @Test
    fun testInvalidOrMissingArguments() = runBlocking {
        registry.register(EchoTool())

        // Missing required 'message' parameter
        val toolCall = ToolCall(
            toolName = "echo",
            arguments = emptyMap(),
            callId = "call-missing-args"
        )

        val result = orchestrator.execute(toolCall)

        assertEquals("echo", result.toolName)
        assertEquals(ToolOrchestrationStatus.FAILURE, result.status)
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertEquals("call-missing-args", result.callId)
        assertTrue(result.result.errorMessage?.contains("Missing required parameter") ?: false)
    }

    @Test
    fun testToolExecutionErrorSafelyHandled() = runBlocking {
        val throwingTool = object : Tool {
            override val name: String = "faulty_tool"
            override val description: String = "Always throws an exception"
            override val definition: ToolDefinition = ToolDefinition(
                name = "faulty_tool",
                description = "Throws an exception",
                parameters = listOf(
                    ToolParameter(name = "trigger", type = ToolParameterType.STRING, isRequired = true)
                )
            )

            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                throw IllegalStateException("Fatal internal tool defect with secret AIzaSy0123456789abcdefghijklmnopqrstuvw")
            }
        }
        registry.register(throwingTool)

        val toolCall = ToolCall(
            toolName = "faulty_tool",
            arguments = mapOf("trigger" to "go"),
            callId = "call-error-1"
        )

        val result = orchestrator.execute(toolCall)

        assertEquals("faulty_tool", result.toolName)
        assertEquals(ToolOrchestrationStatus.FAILURE, result.status)
        assertTrue(result.isFailure)
        assertEquals("call-error-1", result.callId)
        assertNotNull(result.result.errorMessage)
        assertTrue(result.result.errorMessage?.contains("Execution of tool 'faulty_tool' failed") ?: false)
        // Redaction verification
        assertFalse(result.result.errorMessage?.contains("AIzaSy0123456789abcdefghijklmnopqrstuvw") ?: true)
    }

    @Test
    fun testDeterministicResultStructure() = runBlocking {
        registry.register(EchoTool())

        val invocation = ToolInvocation(
            toolName = "echo",
            arguments = mapOf("text" to "Deterministic execution"),
            callId = "call-deterministic"
        )

        val result1 = orchestrator.execute(invocation)
        val result2 = orchestrator.execute(invocation.toolCall)

        assertEquals(result1.toolName, result2.toolName)
        assertEquals(result1.status, result2.status)
        assertEquals(result1.callId, result2.callId)
        assertEquals(result1.result.content, result2.result.content)
        assertEquals(result1.result.status, result2.result.status)
        assertEquals(result1.result.errorMessage, result2.result.errorMessage)
    }
}
