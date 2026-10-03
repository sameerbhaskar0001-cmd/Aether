package com.example.ai.tool.orchestrator

import com.example.ai.tool.model.ToolCall

/**
 * Representation of a tool invocation request, wrapping the underlying [ToolCall]
 * with provider-neutral metadata.
 */
data class ToolInvocation(
    val toolCall: ToolCall
) {
    val toolName: String get() = toolCall.toolName
    val arguments: Map<String, Any?> get() = toolCall.arguments
    val callId: String? get() = toolCall.callId

    constructor(
        toolName: String,
        arguments: Map<String, Any?> = emptyMap(),
        callId: String? = null
    ) : this(ToolCall(toolName = toolName, arguments = arguments, callId = callId))
}
