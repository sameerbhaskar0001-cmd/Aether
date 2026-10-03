package com.example.ai.tool.orchestrator

import com.example.ai.tool.model.ToolResult
import com.example.ai.tool.model.ToolStatus

/**
 * Execution status for tool orchestration cycles.
 */
enum class ToolOrchestrationStatus {
    SUCCESS,
    FAILURE
}

/**
 * Structured, provider-neutral outcome returned by [ToolOrchestrator].
 *
 * @property toolName The name of the invoked tool.
 * @property status The overall orchestration status (SUCCESS or FAILURE).
 * @property result The underlying standardized [ToolResult] containing execution details.
 * @property callId Optional call ID correlation tag.
 */
data class ToolOrchestrationResult(
    val toolName: String,
    val status: ToolOrchestrationStatus,
    val result: ToolResult,
    val callId: String? = result.callId
) {
    val isSuccess: Boolean get() = status == ToolOrchestrationStatus.SUCCESS
    val isFailure: Boolean get() = status == ToolOrchestrationStatus.FAILURE

    companion object {
        /**
         * Wraps a [ToolResult] into a deterministic [ToolOrchestrationResult].
         */
        fun fromToolResult(result: ToolResult): ToolOrchestrationResult {
            val orchestrationStatus = if (result.isSuccess) {
                ToolOrchestrationStatus.SUCCESS
            } else {
                ToolOrchestrationStatus.FAILURE
            }
            return ToolOrchestrationResult(
                toolName = result.toolName,
                status = orchestrationStatus,
                result = result,
                callId = result.callId
            )
        }
    }
}
