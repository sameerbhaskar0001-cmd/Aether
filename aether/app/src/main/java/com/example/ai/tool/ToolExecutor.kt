package com.example.ai.tool

import android.util.Log
import com.example.ai.tool.model.ToolCall
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.util.PrivacyUtil

/**
 * Responsible for safely validating and executing [ToolCall] requests against registered tools.
 * Isolates the application from tool crashes, sanitizes exceptions, and enforces security boundaries.
 */
class ToolExecutor(
    private val registry: ToolRegistry
) {
    companion object {
        private const val TAG = "ToolExecutor"
    }

    /**
     * Validates and executes a tool call.
     *
     * Input Validation & Security Rules:
     * 1. Unknown tool names return a controlled [ToolResult.error] without throwing.
     * 2. Required parameters are strictly enforced; missing required arguments return [ToolResult.error].
     * 3. Parameter data types are validated against schema definitions.
     * 4. Unknown/extra argument fields are tolerated (ignored by validation and passed to tool).
     * 5. Any runtime exception thrown by a tool is intercepted, logged with sensitive token redaction,
     *    and converted into a safe [ToolResult.error].
     *
     * @param toolCall The tool call request.
     * @return Standardized [ToolResult].
     */
    suspend fun execute(toolCall: ToolCall): ToolResult {
        val toolName = toolCall.toolName.trim()
        val callId = toolCall.callId

        if (toolName.isBlank()) {
            return ToolResult.error(
                toolName = "",
                errorMessage = "Tool call rejected: toolName cannot be blank.",
                callId = callId
            )
        }

        val tool = registry.get(toolName)
        if (tool == null) {
            return ToolResult.error(
                toolName = toolName,
                errorMessage = "Tool '$toolName' is not registered.",
                callId = callId
            )
        }

        // Validate arguments against tool definition schema
        val validationError = validateArguments(tool.definition.parameters, toolCall.arguments, toolName)
        if (validationError != null) {
            return ToolResult.error(
                toolName = toolName,
                errorMessage = validationError,
                callId = callId
            )
        }

        return try {
            val rawResult = tool.execute(toolCall.arguments)
            // Ensure callId is preserved if not populated by tool implementation
            if (rawResult.callId == null && callId != null) {
                rawResult.copy(callId = callId)
            } else {
                rawResult
            }
        } catch (t: Throwable) {
            val rawMessage = t.localizedMessage ?: t.message ?: "Tool execution failed"
            val sanitizedMessage = PrivacyUtil.sanitizeErrorMessage(rawMessage)
            Log.e(TAG, "Exception during execution of tool '$toolName': $sanitizedMessage", t)
            ToolResult.error(
                toolName = toolName,
                errorMessage = "Execution of tool '$toolName' failed: $sanitizedMessage",
                callId = callId
            )
        }
    }

    /**
     * Validates arguments against parameter definitions.
     * Returns null if valid, or an error description string if invalid.
     */
    internal fun validateArguments(
        parameters: List<ToolParameter>,
        arguments: Map<String, Any?>,
        toolName: String
    ): String? {
        for (param in parameters) {
            val value = arguments[param.name]
            if (param.isRequired && (value == null || (value is String && value.isBlank()))) {
                return "Missing required parameter '${param.name}' for tool '$toolName'."
            }

            if (value != null) {
                val typeValid = when (param.type) {
                    ToolParameterType.STRING -> value is CharSequence
                    ToolParameterType.NUMBER -> value is Number || (value is String && value.toDoubleOrNull() != null)
                    ToolParameterType.BOOLEAN -> value is Boolean || (value is String && (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)))
                    ToolParameterType.ARRAY -> value is Collection<*> || value is Array<*>
                    ToolParameterType.OBJECT -> value is Map<*, *>
                }

                if (!typeValid) {
                    return "Invalid type for parameter '${param.name}' on tool '$toolName'. Expected ${param.type.name} but received ${value::class.java.simpleName}."
                }
            }
        }
        return null
    }
}
