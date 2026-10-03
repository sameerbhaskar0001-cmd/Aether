package com.example.ai.tool

import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolResult

/**
 * Common, provider-neutral contract for all tools in Aether.
 * Tools are independent of Gemini, Groq, or any specific AI model.
 */
interface Tool {
    /**
     * Stable, unique identifier of the tool (e.g., "echo", "calculator").
     */
    val name: String

    /**
     * Human/model-readable description of what the tool accomplishes.
     */
    val description: String

    /**
     * Formal schema definition describing required/optional parameters.
     */
    val definition: ToolDefinition

    /**
     * Safely executes the tool with untrusted input arguments.
     * Must not throw uncaught fatal exceptions or compromise system security.
     *
     * @param arguments Map of parameter names to supplied values.
     * @return Standardized [ToolResult] containing either output or error details.
     */
    suspend fun execute(arguments: Map<String, Any?>): ToolResult
}
