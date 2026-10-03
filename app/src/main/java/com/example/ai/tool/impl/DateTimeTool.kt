package com.example.ai.tool.impl

import com.example.ai.TimeProvider
import com.example.ai.SystemTimeProvider
import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Deterministic timezone-aware system clock tool.
 * Accepts TimeProvider for robust testability.
 */
class DateTimeTool(
    private val timeProvider: TimeProvider = SystemTimeProvider()
) : Tool {
    override val name: String = "get_current_time"
    override val description: String = "Retrieves the current system date and time, with optional timezone support."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "timezone",
                type = ToolParameterType.STRING,
                description = "Optional custom timezone ID (e.g. 'UTC', 'GMT', 'America/New_York'). Defaults to the system local timezone.",
                isRequired = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val timezoneIdInput = arguments["timezone"]?.toString()?.trim()
        val systemTimezone = timeProvider.getZoneId()

        val timeZone = if (!timezoneIdInput.isNullOrBlank()) {
            val tz = TimeZone.getTimeZone(timezoneIdInput)
            // TimeZone.getTimeZone returns "GMT" for unknown IDs, fallback gracefully
            if (tz.id == "GMT" && timezoneIdInput != "GMT" && timezoneIdInput != "UTC") {
                TimeZone.getTimeZone(systemTimezone)
            } else {
                tz
            }
        } else {
            TimeZone.getTimeZone(systemTimezone)
        }

        val currentTimeMs = timeProvider.currentTimeMillis()
        val date = Date(currentTimeMs)

        // Structured timezone-aware formatting
        val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).apply {
            setTimeZone(timeZone)
        }
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            setTimeZone(timeZone)
        }
        val friendlyFormat = SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).apply {
            setTimeZone(timeZone)
        }

        val resultJson = JSONObject().apply {
            put("timestamp_ms", currentTimeMs)
            put("timezone", timeZone.id)
            put("formatted", fullFormat.format(date))
            put("iso_8601", isoFormat.format(date))
            put("friendly", friendlyFormat.format(date))
        }

        return ToolResult.success(name, resultJson.toString())
    }
}
