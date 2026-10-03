package com.example.ai.tool.impl

import android.content.Context
import com.example.ai.tool.Tool
import com.example.ai.tool.model.ToolDefinition
import com.example.ai.tool.model.ToolParameter
import com.example.ai.tool.model.ToolParameterType
import com.example.ai.tool.model.ToolResult
import com.example.data.local.AppDatabase
import com.example.data.local.entity.NoteEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic local tool for creating, reading, updating, deleting and searching notes.
 * Respects Incognito mode with isolated in-memory transient containment.
 */
class NotesTool(
    private val context: Context? = null
) : Tool {

    override val name: String = "manage_notes"
    override val description: String = "Manages local personal notes. Supports actions: 'create' (needs title/content), 'read' (needs id), 'update' (needs id, optional title/content), 'delete' (needs id), 'list' (all), and 'search' (needs query)."

    override val definition: ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The specific database operation to perform. One of: 'create', 'read', 'update', 'delete', 'list', 'search'.",
                isRequired = true
            ),
            ToolParameter(
                name = "id",
                type = ToolParameterType.STRING,
                description = "The unique identifier of the note (required for 'read', 'update', 'delete').",
                isRequired = false
            ),
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "The title of the note (optional for 'update', recommended for 'create').",
                isRequired = false
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "The text body of the note (optional for 'update', recommended for 'create').",
                isRequired = false
            ),
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The text query keywords (required for 'search').",
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

    private val noteDao = context?.let { AppDatabase.getDatabase(it).noteDao() }
    
    // In-memory isolated storage for Incognito mode and test fallbacks
    private val incognitoNotes = ConcurrentHashMap<String, NoteEntity>()

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
        val action = arguments["action"]?.toString()?.trim()?.lowercase() ?: ""
        val id = arguments["id"]?.toString()?.trim()
        val title = arguments["title"]?.toString() ?: ""
        val content = arguments["content"]?.toString() ?: ""
        val query = arguments["query"]?.toString() ?: ""
        
        val isIncognito = arguments["is_incognito"] as? Boolean ?: false
        val useInMemory = isIncognito || noteDao == null

        if (action.isBlank()) {
            return ToolResult.error(name, "Missing required parameter 'action'.")
        }

        return try {
            when (action) {
                "create" -> {
                    val noteId = if (id.isNullOrBlank()) UUID.randomUUID().toString() else id
                    val now = System.currentTimeMillis()
                    val note = NoteEntity(noteId, title, content, now, now)

                    if (useInMemory) {
                        incognitoNotes[noteId] = note
                    } else {
                        noteDao?.insert(note)
                    }

                    val json = JSONObject().apply {
                        put("status", "created")
                        put("id", noteId)
                        put("title", title)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "read" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'read'.")
                    }
                    val note = if (useInMemory) {
                        incognitoNotes[id]
                    } else {
                        noteDao?.getById(id)
                    }

                    if (note == null) {
                        return ToolResult.error(name, "Note with ID '$id' not found.")
                    }

                    val json = JSONObject().apply {
                        put("id", note.id)
                        put("title", note.title)
                        put("content", note.content)
                        put("createdAt", note.createdAt)
                        put("updatedAt", note.updatedAt)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "update" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'update'.")
                    }
                    val existing = if (useInMemory) {
                        incognitoNotes[id]
                    } else {
                        noteDao?.getById(id)
                    }

                    if (existing == null) {
                        return ToolResult.error(name, "Note with ID '$id' not found.")
                    }

                    val updatedTitle = if (title.isNotEmpty()) title else existing.title
                    val updatedContent = if (content.isNotEmpty()) content else existing.content
                    val now = System.currentTimeMillis()

                    val updatedNote = NoteEntity(
                        id = id,
                        title = updatedTitle,
                        content = updatedContent,
                        createdAt = existing.createdAt,
                        updatedAt = now
                    )

                    if (useInMemory) {
                        incognitoNotes[id] = updatedNote
                    } else {
                        noteDao?.update(updatedNote)
                    }

                    val json = JSONObject().apply {
                        put("status", "updated")
                        put("id", id)
                        put("title", updatedTitle)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "delete" -> {
                    if (id.isNullOrBlank()) {
                        return ToolResult.error(name, "Parameter 'id' is required for action 'delete'.")
                    }

                    val existed = if (useInMemory) {
                        incognitoNotes.remove(id) != null
                    } else {
                        val exists = noteDao?.getById(id) != null
                        if (exists) noteDao?.deleteById(id)
                        exists
                    }

                    if (!existed) {
                        return ToolResult.error(name, "Note with ID '$id' not found.")
                    }

                    val json = JSONObject().apply {
                        put("status", "deleted")
                        put("id", id)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "list" -> {
                    val list = if (useInMemory) {
                        incognitoNotes.values.sortedByDescending { it.updatedAt }
                    } else {
                        noteDao?.getAll() ?: emptyList()
                    }

                    val array = JSONArray()
                    list.forEach { note ->
                        array.put(JSONObject().apply {
                            put("id", note.id)
                            put("title", note.title)
                            put("content", note.content)
                            put("updatedAt", note.updatedAt)
                        })
                    }

                    val json = JSONObject().apply {
                        put("notes", array)
                        put("count", list.size)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                "search" -> {
                    if (query.isBlank()) {
                        return ToolResult.error(name, "Parameter 'query' is required for action 'search'.")
                    }

                    val list = if (useInMemory) {
                        incognitoNotes.values.filter {
                            it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true)
                        }.sortedByDescending { it.updatedAt }
                    } else {
                        noteDao?.search(query) ?: emptyList()
                    }

                    val array = JSONArray()
                    list.forEach { note ->
                        array.put(JSONObject().apply {
                            put("id", note.id)
                            put("title", note.title)
                            put("content", note.content)
                            put("updatedAt", note.updatedAt)
                        })
                    }

                    val json = JSONObject().apply {
                        put("notes", array)
                        put("query", query)
                        put("count", list.size)
                        put("is_incognito", isIncognito)
                    }
                    ToolResult.success(name, json.toString())
                }
                else -> {
                    ToolResult.error(name, "Unsupported action '$action'. Available actions: create, read, update, delete, list, search.")
                }
            }
        } catch (e: Exception) {
            ToolResult.error(name, "Notes operation failed: ${e.localizedMessage ?: "Unknown database error"}")
        }
    }
}
