package com.example.ai.tool.impl

import android.content.Context
import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.data.local.AppDatabase
import com.example.data.local.entity.TaskEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic local tool for task and to-do lists.
 * Supports CRUD and state controls, with isolated in-memory containment for Incognito mode.
 */
class TasksTool(
    private val context: Context? = null
) : Tool {

    override val name: String = "manage_tasks"
    override val description: String = "Manages personal tasks. Actions: 'create' (needs title, optional content/due_date), 'list' (all), 'update' (needs id, optional fields), 'complete' (needs id), 'delete' (needs id)."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The specific operation to perform. One of: 'create', 'list', 'update', 'complete', 'delete'.",
                isRequired = true
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "The unique identifier of the task (required for 'update', 'complete', 'delete').",
                isRequired = false
            ),
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "The title/name of the task (required for 'create').",
                isRequired = false
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "Additional description or notes for the task.",
                isRequired = false
            ),
            ToolParameter(
                name = "due_date",
                type = ToolParameterType.STRING,
                description = "Optional deadline. Support formats: 'yyyy-MM-dd HH:mm' or 'yyyy-MM-dd'.",
                isRequired = false
            ),
            ToolParameter(
                name = "is_completed",
                type = ToolParameterType.BOOLEAN,
                description = "Sets the completion status (optional for 'update').",
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

    private val taskDao = context?.let { AppDatabase.getDatabase(it).taskDao() }
    
    // In-memory isolated storage for Incognito mode and test fallbacks
    private val incognitoTasks = ConcurrentHashMap<String, TaskEntity>()

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: ""
        val id = arguments["id"]?.toString()?.trim()
        val title = arguments["title"]?.toString() ?: ""
        val content = arguments["content"]?.toString() ?: ""
        val dueDateString = arguments["due_date"]?.toString()?.trim()
        val isCompletedArg = arguments["is_completed"] as? Boolean
        
        val isIncognito = arguments["is_incognito"] as? Boolean ?: false
        val useInMemory = isIncognito || taskDao == null

        if (action.isBlank()) {
            return ToolResult.error(name, "Missing required parameter 'action'.")
        }

        return try {
            when (action) {
                "create" -> {
                    if (title.isBlank()) {
                        return ToolResult.error(name, "Parameter 'title' is required for action 'create'.")
                    }
                    val taskId = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
                    val now = System.currentTimeMillis()
                    
                    val dueDateParsed = dueDateString?.let { parseDueDate(it) }

                    val task = TaskEntity(
                        id = taskId,
                        title = title,
                        content = content,
                        isCompleted = false,
                        dueDate = dueDateParsed,
                        createdAt = now,
                        updatedAt = now
                    )

                    if (useInMemory) {
                        incognitoTasks[taskId] = task
                    } else {
                        taskDao?.insert(task)
                    }

                    val json = JSONObject().apply {
                        put("status", "created")
                        put("id", taskId)
                        put("title", title)
                        put("due_date", dueDateParsed)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "list" -> {
                    val list = if (useInMemory) {
                        incognitoTasks.values.sortedByDescending { it.createdAt }
                    } else {
                        taskDao?.getAll() ?: emptyList()
                    }

                    val array = JSONArray()
                    list.forEach { task ->
                        array.put(JSONObject().apply {
                            put("id", task.id)
                            put("title", task.title)
                            put("content", task.content)
                            put("is_completed", task.isCompleted)
                            put("due_date", task.dueDate)
                            put("createdAt", task.createdAt)
                        })
                    }

                    val json = JSONObject().apply {
                        put("tasks", array)
                        put("count", list.size)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "update" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'update'.")
                    }
                    val existing = if (useInMemory) {
                        incognitoTasks[id]
                    } else {
                        taskDao?.getById(id)
                    }

                    if (existing == null) {
                        return ToolResult.error(name, "Task with ID '$id' not found.")
                    }

                    val updatedTitle = if (title.isNotEmpty()) title else existing.title
                    val updatedContent = if (content.isNotEmpty()) content else existing.content
                    val updatedCompleted = isCompletedArg ?: existing.isCompleted
                    val updatedDueDate = if (dueDateString != null) {
                        if (dueDateString.isEmpty()) null else parseDueDate(dueDateString)
                    } else {
                        existing.dueDate
                    }
                    val now = System.currentTimeMillis()

                    val updatedTask = TaskEntity(
                        id = id,
                        title = updatedTitle,
                        content = updatedContent,
                        isCompleted = updatedCompleted,
                        dueDate = updatedDueDate,
                        createdAt = existing.createdAt,
                        updatedAt = now
                    )

                    if (useInMemory) {
                        incognitoTasks[id] = updatedTask
                    } else {
                        taskDao?.update(updatedTask)
                    }

                    val json = JSONObject().apply {
                        put("status", "updated")
                        put("id", id)
                        put("title", updatedTitle)
                        put("is_completed", updatedCompleted)
                        put("due_date", updatedDueDate)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "complete" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'complete'.")
                    }
                    val existing = if (useInMemory) {
                        incognitoTasks[id]
                    } else {
                        taskDao?.getById(id)
                    }

                    if (existing == null) {
                        return ToolResult.error(name, "Task with ID '$id' not found.")
                    }

                    val now = System.currentTimeMillis()
                    val updatedTask = existing.copy(isCompleted = true, updatedAt = now)

                    if (useInMemory) {
                        incognitoTasks[id] = updatedTask
                    } else {
                        taskDao?.update(updatedTask)
                    }

                    val json = JSONObject().apply {
                        put("status", "completed")
                        put("id", id)
                        put("title", existing.title)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "delete" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'delete'.")
                    }

                    val existed = if (useInMemory) {
                        incognitoTasks.remove(id) != null
                    } else {
                        val exists = taskDao?.getById(id) != null
                        if (exists) taskDao?.deleteById(id)
                        exists
                    }

                    if (!existed) {
                        return ToolResult.error(name, "Task with ID '$id' not found.")
                    }

                    val json = JSONObject().apply {
                        put("status", "deleted")
                        put("id", id)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                else -> {
                    ToolResult.error(name, "Unsupported action '$action'. Available actions: create, list, update, complete, delete.")
                }
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Tasks operation failed: ${e.localizedMessage ?: "Unknown database error"}")
        }
    }

    private fun parseDueDate(dateStr: String): Long {
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        for (format in formats) {
            try {
                return format.parse(dateStr)?.time ?: continue
            } catch (e: Exception) {
                // Try next format
            }
        }
        // Return 0 or current time as safety fallback if completely malformed
        return System.currentTimeMillis()
    }
}
