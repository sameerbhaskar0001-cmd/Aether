package com.example.ai.tool.orchestrator

import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.model.ToolCall

/**
 * Minimal, provider-neutral orchestrator that executes a [ToolCall] or [ToolInvocation]
 * through the existing [ToolExecutor].
 *
 * Security & Design Rules:
 * 1. Provider-neutral (zero Gemini or Groq specific logic).
 * 2. Unknown tools and invalid arguments utilize the standard [ToolExecutor] error handling.
 * 3. Does not execute arbitrary code, shell commands, URLs, or reflection.
 * 4. Safe for coroutine dispatchers and fully deterministic.
 */
class ToolOrchestrator(
    private val toolExecutor: ToolExecutor
) {
    /**
     * Executes a single [ToolCall] and packages the outcome into a [ToolOrchestrationResult].
     *
     * @param toolCall The tool call request to execute.
     * @return Standardized [ToolOrchestrationResult].
     */
    suspend fun execute(toolCall: ToolCall): ToolOrchestrationResult {
        val toolResult = toolExecutor.execute(toolCall)
        return ToolOrchestrationResult.fromToolResult(toolResult)
    }

    /**
     * Executes a single [ToolInvocation] and packages the outcome into a [ToolOrchestrationResult].
     *
     * @param invocation The invocation request.
     * @return Standardized [ToolOrchestrationResult].
     */
    suspend fun execute(invocation: ToolInvocation): ToolOrchestrationResult {
        return execute(invocation.toolCall)
    }

    /**
     * Executes a list of [ToolCall] requests sequentially in deterministic order.
     *
     * @param toolCalls The list of tool calls to execute.
     * @return A list of [ToolOrchestrationResult] in corresponding order.
     */
    suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolOrchestrationResult> {
        return toolCalls.map { execute(it) }
    }
}
