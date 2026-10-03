package com.example.ai.tool.impl

import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic duration-based timers tool.
 * Operates entirely locally with in-memory active tracking.
 */
class TimersTool : Tool {

    override val name: String = "manage_timers"
    override val description: String = "Controls local duration-based timers. Actions: 'start' (needs duration_seconds, optional label), 'query' (optional label), 'cancel' (needs label/id)."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The timer control action. One of: 'start', 'query', 'cancel'.",
                isRequired = true
            ),
            ToolParameter(
                name = "duration_seconds",
                type = ToolParameterType.NUMBER,
                description = "The countdown duration in seconds (required for 'start').",
                isRequired = false
            ),
            ToolParameter(
                name = "label",
                type = ToolParameterType.STRING,
                description = "An optional identifier or label for the timer.",
                isRequired = false
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "The unique identifier of the timer (optional, can cancel by id).",
                isRequired = false
            )
        )
    )

    companion object {
        // Active timers shared across tool executions to maintain consistent state
        private val activeTimers = ConcurrentHashMap<String, TimerRecord>()
    }

    private data class TimerRecord(
        val id: String,
        val label: String,
        val startTimeMs: Long,
        val durationSeconds: Long
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: ""
        val durationRaw = arguments["duration_seconds"]
        val labelInput = arguments["label"]?.toString()?.trim() ?: ""
        val idInput = arguments["id"]?.toString()?.trim() ?: ""

        if (action.isBlank()) {
            return ToolResult.error(name, "Missing required parameter 'action'.")
        }

        return try {
            when (action) {
                "start" -> {
                    val durationSec = when (durationRaw) {
                        is Number -> durationRaw.toLong()
                        is String -> durationRaw.toDoubleOrNull()?.toLong()
                        else -> null
                    }

                    if (durationSec == null || durationSec <= 0) {
                        return ToolResult.error(name, "A positive duration in seconds is required to start a timer.")
                    }

                    val timerId = UUID.randomUUID().toString()
                    val label = if (labelInput.isEmpty()) "Timer-$timerId" else labelInput
                    val now = System.currentTimeMillis()

                    val record = TimerRecord(
                        id = timerId,
                        label = label,
                        startTimeMs = now,
                        durationSeconds = durationSec
                    )

                    activeTimers[timerId] = record

                    val json = JSONObject().apply {
                        put("status", "started")
                        put("id", timerId)
                        put("label", label)
                        put("duration_seconds", durationSec)
                        put("ends_at", now + (durationSec * 1000))
                    }
                    ToolResult.success(name, json.toString())
                }
                "query" -> {
                    val now = System.currentTimeMillis()
                    val array = JSONArray()

                    // Update and clean expired timers on the fly
                    val entries = activeTimers.values.toList()
                    entries.forEach { timer ->
                        val elapsedMs = now - timer.startTimeMs
                        val elapsedSec = elapsedMs / 1000
                        val remainingSec = (timer.durationSeconds - elapsedSec).coerceAtLeast(0)

                        if (remainingSec == 0L) {
                            activeTimers.remove(timer.id) // Clean up finished timers
                        }

                        // Filter by label if requested
                        if (labelInput.isEmpty() || timer.label.contains(labelInput, ignoreCase = true)) {
                            array.put(JSONObject().apply {
                                put("id", timer.id)
                                put("label", timer.label)
                                put("duration_seconds", timer.durationSeconds)
                                put("remaining_seconds", remainingSec)
                                put("is_expired", remainingSec == 0L)
                            })
                        }
                    }

                    val json = JSONObject().apply {
                        put("timers", array)
                        put("count", array.length())
                    }
                    ToolResult.success(name, json.toString())
                }
                "cancel" -> {
                    if (idInput.isEmpty() && labelInput.isEmpty()) {
                        return ToolResult.error(name, "Either 'id' or 'label' is required to cancel a timer.")
                    }

                    var cancelledCount = 0
                    val entries = activeTimers.values.toList()
                    entries.forEach { timer ->
                        val matchesId = idInput.isNotEmpty() && timer.id == idInput
                        val matchesLabel = labelInput.isNotEmpty() && timer.label.equals(labelInput, ignoreCase = true)
                        
                        if (matchesId || matchesLabel) {
                            activeTimers.remove(timer.id)
                            cancelledCount++
                        }
                    }

                    val json = JSONObject().apply {
                        put("status", "cancelled")
                        put("cancelled_count", cancelledCount)
                    }
                    ToolResult.success(name, json.toString())
                }
                else -> {
                    ToolResult.error(name, "Unsupported action '$action'. Available actions: start, query, cancel.")
                }
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Timers operation failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
