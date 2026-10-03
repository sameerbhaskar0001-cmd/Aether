package com.example.ai.tool.model

/**
 * Supported data types for tool parameter inputs.
 */
enum class ToolParameterType {
    STRING,
    NUMBER,
    BOOLEAN,
    ARRAY,
    OBJECT
}

/**
 * Specification for a single input parameter expected by a tool.
 */
data class ToolParameter(
    val name: String,
    val type: ToolParameterType,
    val description: String = "",
    val isRequired: Boolean = true
)

/**
 * Complete definition of a tool including its stable identity, purpose description,
 * and parameter specifications.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * Representation of an invocation request for a tool.
 */
data class ToolCall(
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val callId: String? = null
)

/**
 * Standard execution status of a tool.
 */
enum class ToolStatus {
    SUCCESS,
    ERROR
}

/**
 * Standardized, provider-neutral execution outcome returned by a tool or executor.
 */
data class ToolResult(
    val toolName: String,
    val status: ToolStatus,
    val content: String,
    val callId: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = status == ToolStatus.SUCCESS
    val isError: Boolean get() = status == ToolStatus.ERROR

    companion object {
        fun success(
            toolName: String,
            content: String,
            callId: String? = null,
            metadata: Map<String, Any?> = emptyMap()
        ): ToolResult {
            return ToolResult(
                toolName = toolName,
                status = ToolStatus.SUCCESS,
                content = content,
                callId = callId,
                metadata = metadata,
                errorMessage = null
            )
        }

        fun error(
            toolName: String,
            errorMessage: String,
            callId: String? = null,
            metadata: Map<String, Any?> = emptyMap()
        ): ToolResult {
            return ToolResult(
                toolName = toolName,
                status = ToolStatus.ERROR,
                content = "",
                callId = callId,
                metadata = metadata,
                errorMessage = errorMessage
            )
        }
    }
}
