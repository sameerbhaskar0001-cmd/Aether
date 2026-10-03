package com.example.ai.tool.impl

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult

/**
 * Deterministic test tool that echoes back provided text input.
 * Does not access network, storage, device APIs, or secrets.
 */
class EchoTool : Tool {
    override val name: String = "echo"
    override val description: String = "Echoes the provided text message back as the tool execution result."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "text",
                type = ToolParameterType.STRING,
                description = "The text string to echo back.",
                isRequired = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val input = arguments["text"]?.toString() ?: ""
        return ToolResult.success(
            toolName = name,
            content = input
        )
    }
}
