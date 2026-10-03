package com.example.ai.tool.impl

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.data.local.AppDatabase
import com.example.data.local.entity.ReminderEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic local reminders tool.
 * Schedules push notifications via Android AlarmManager and respects Incognito boundaries.
 */
class RemindersTool(
    private val context: Context? = null
) : Tool {

    override val name: String = "manage_reminders"
    override val description: String = "Schedules personal reminders. Actions: 'create' (needs title, time), 'list' (all), 'retrieve' (needs id), 'cancel' (needs id)."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The specific database/alarm operation. One of: 'create', 'list', 'retrieve', 'cancel'.",
                isRequired = true
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "The unique identifier of the reminder (required for 'retrieve', 'cancel').",
                isRequired = false
            ),
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "The message of the reminder (required for 'create').",
                isRequired = false
            ),
            ToolParameter(
                name = "time",
                type = ToolParameterType.STRING,
                description = "The target notification time. Formats: 'yyyy-MM-dd HH:mm' or number of relative minutes (e.g., '15' for in 15 minutes).",
                isRequired = false
            ),
            ToolParameter(
                name = "is_incognito",
                type = ToolParameterType.BOOLEAN,
                description = "Optional flag indicating whether this operation runs in Incognito mode.",
                isRequired = false
            )
        )
    )

    private val reminderDao = context?.let { AppDatabase.getDatabase(it).reminderDao() }
    
    // In-memory isolated storage for Incognito mode and test fallbacks
    private val incognitoReminders = ConcurrentHashMap<String, ReminderEntity>()

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: ""
        val id = arguments["id"]?.toString()?.trim()
        val title = arguments["title"]?.toString() ?: ""
        val timeString = arguments["time"]?.toString()?.trim() ?: ""
        
        val isIncognito = arguments["is_incognito"] as? Boolean ?: false
        val useInMemory = isIncognito || reminderDao == null

        if (action.isBlank()) {
            return ToolResult.error(name, "Missing required parameter 'action'.")
        }

        return try {
            when (action) {
                "create" -> {
                    if (title.isBlank()) {
                        return ToolResult.error(name, "Parameter 'title' is required for action 'create'.")
                    }
                    if (timeString.isBlank()) {
                        return ToolResult.error(name, "Parameter 'time' is required for action 'create'.")
                    }

                    val now = System.currentTimeMillis()
                    val triggerTime = parseTriggerTime(timeString, now)

                    if (triggerTime <= now) {
                        return ToolResult.error(name, "Invalid trigger time: '$timeString'. Specified time must be in the future.")
                    }

                    val reminderId = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
                    val reminder = ReminderEntity(
                        id = reminderId,
                        title = title,
                        triggerTime = triggerTime,
                        isCompleted = false,
                        createdAt = now
                    )

                    // 1. Save Reminder
                    if (useInMemory) {
                        incognitoReminders[reminderId] = reminder
                    } else {
                        reminderDao?.insert(reminder)
                    }

                    // 2. Schedule Notification via AlarmManager
                    if (context != null) {
                        scheduleAlarm(context, reminderId, title, triggerTime)
                    }

                    val json = JSONObject().apply {
                        put("status", "created")
                        put("id", reminderId)
                        put("title", title)
                        put("trigger_time", triggerTime)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "list" -> {
                    val list = if (useInMemory) {
                        incognitoReminders.values.sortedBy { it.triggerTime }
                    } else {
                        reminderDao?.getAll() ?: emptyList()
                    }

                    val array = JSONArray()
                    list.forEach { rem ->
                        array.put(JSONObject().apply {
                            put("id", rem.id)
                            put("title", rem.title)
                            put("triggerTime", rem.triggerTime)
                            put("isCompleted", rem.isCompleted)
                        })
                    }

                    val json = JSONObject().apply {
                        put("reminders", array)
                        put("count", list.size)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "retrieve" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'retrieve'.")
                    }
                    val rem = if (useInMemory) {
                        incognitoReminders[id]
                    } else {
                        reminderDao?.getById(id)
                    }

                    if (rem == null) {
                        return ToolResult.error(name, "Reminder with ID '$id' not found.")
                    }

                    val json = JSONObject().apply {
                        put("id", rem.id)
                        put("title", rem.title)
                        put("triggerTime", rem.triggerTime)
                        put("isCompleted", rem.isCompleted)
                        put("createdAt", rem.createdAt)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "cancel" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'cancel'.")
                    }

                    val existed = if (useInMemory) {
                        incognitoReminders.remove(id) != null
                    } else {
                        val exists = reminderDao?.getById(id) != null
                        if (exists) reminderDao?.deleteById(id)
                        exists
                    }

                    if (!existed) {
                        return ToolResult.error(name, "Reminder with ID '$id' not found.")
                    }

                    // Cancel pending intent
                    if (context != null) {
                        cancelAlarm(context, id)
                    }

                    val json = JSONObject().apply {
                        put("status", "cancelled")
                        put("id", id)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                else -> {
                    ToolResult.error(name, "Unsupported action '$action'. Available actions: create, list, retrieve, cancel.")
                }
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Reminders operation failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun parseTriggerTime(timeStr: String, nowMs: Long): Long {
        // Try relative minutes first
        val relativeMin = timeStr.toLongOrNull()
        if (relativeMin != null) {
            return nowMs + (relativeMin * 60 * 1000)
        }

        // Try absolute date/time format
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (format in formats) {
            try {
                return format.parse(timeStr)?.time ?: continue
            } catch (e: Exception) {
                // Try next
            }
        }
        return 0L
    }

    private fun scheduleAlarm(context: Context, id: String, title: String, triggerTime: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("id", id)
                putExtra("title", title)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } catch (se: SecurityException) {
                    // Fallback to inexact alarm if exact alarm permissions are restricted on Android 12+
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                }
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            // Log or silently ignore scheduling errors during mock tests
        }
    }

    private fun cancelAlarm(context: Context, id: String) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (e: Exception) {
            // Silently ignore during mock tests
        }
    }
}
