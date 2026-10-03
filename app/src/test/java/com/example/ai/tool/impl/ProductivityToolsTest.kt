package com.example.ai.tool.impl

import com.example.ai.tool.ToolExecutor
import com.example.ai.tool.ToolRegistry
import com.example.ai.tool.model.ToolCall
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProductivityToolsTest {

    private lateinit var registry: ToolRegistry
    private lateinit var executor: ToolExecutor

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        registry = ToolRegistry(context, registerDefaults = true)
        executor = ToolExecutor(registry)
    }

    // --- NOTES TOOL TESTS ---

    @Test
    fun testNotesCRUDAndSearch() = runBlocking {
        // 1. Create a Note
        val createCall = ToolCall("manage_notes", mapOf(
            "action" to "create",
            "id" to "note-123",
            "title" to "Aether Specs",
            "content" to "Local and private productivity."
        ))
        val createResult = executor.execute(createCall)
        assertTrue(createResult.isSuccess)

        // 2. Read Note
        val readCall = ToolCall("manage_notes", mapOf(
            "action" to "read",
            "id" to "note-123"
        ))
        val readResult = executor.execute(readCall)
        assertTrue(readResult.isSuccess)
        val readJson = JSONObject(readResult.content)
        assertEquals("Aether Specs", readJson.getString("title"))
        assertEquals("Local and private productivity.", readJson.getString("content"))

        // 3. Search Note
        val searchCall = ToolCall("manage_notes", mapOf(
            "action" to "search",
            "query" to "private"
        ))
        val searchResult = executor.execute(searchCall)
        assertTrue(searchResult.isSuccess)
        val searchJson = JSONObject(searchResult.content)
        assertEquals(1, searchJson.getInt("count"))

        // 4. Update Note
        val updateCall = ToolCall("manage_notes", mapOf(
            "action" to "update",
            "id" to "note-123",
            "title" to "Updated Specs",
            "content" to "New content body."
        ))
        val updateResult = executor.execute(updateCall)
        assertTrue(updateResult.isSuccess)

        // 5. Delete Note
        val deleteCall = ToolCall("manage_notes", mapOf(
            "action" to "delete",
            "id" to "note-123"
        ))
        val deleteResult = executor.execute(deleteCall)
        assertTrue(deleteResult.isSuccess)
    }

    @Test
    fun testNotesIncognitoIsolation() = runBlocking {
        // Create in Incognito
        val createCall = ToolCall("manage_notes", mapOf(
            "action" to "create",
            "id" to "incog-note-1",
            "title" to "Secret Plan",
            "content" to "Classified material.",
            "is_incognito" to true
        ))
        val createResult = executor.execute(createCall)
        assertTrue(createResult.isSuccess)

        // Read inside Incognito
        val readCall = ToolCall("manage_notes", mapOf(
            "action" to "read",
            "id" to "incog-note-1",
            "is_incognito" to true
        ))
        val readResult = executor.execute(readCall)
        assertTrue(readResult.isSuccess)

        // Attempting to read WITHOUT incognito flag should fail (isolated in memory only!)
        val readNoIncogCall = ToolCall("manage_notes", mapOf(
            "action" to "read",
            "id" to "incog-note-1"
        ))
        val readNoIncogResult = executor.execute(readNoIncogCall)
        assertTrue(readNoIncogResult.isError)
    }

    // --- TASKS TOOL TESTS ---

    @Test
    fun testTasksCRUDAndCompletion() = runBlocking {
        // 1. Create a Task
        val createCall = ToolCall("manage_tasks", mapOf(
            "action" to "create",
            "id" to "task-123",
            "title" to "Review Code",
            "content" to "Ensure zero security regressions.",
            "due_date" to "2026-10-02 15:00"
        ))
        val createResult = executor.execute(createCall)
        assertTrue(createResult.isSuccess)

        // 2. List Tasks
        val listCall = ToolCall("manage_tasks", mapOf("action" to "list"))
        val listResult = executor.execute(listCall)
        assertTrue(listResult.isSuccess)
        val listJson = JSONObject(listResult.content)
        assertTrue(listJson.getInt("count") >= 1)

        // 3. Complete Task
        val completeCall = ToolCall("manage_tasks", mapOf(
            "action" to "complete",
            "id" to "task-123"
        ))
        val completeResult = executor.execute(completeCall)
        assertTrue(completeResult.isSuccess)

        // 4. Delete Task
        val deleteCall = ToolCall("manage_tasks", mapOf(
            "action" to "delete",
            "id" to "task-123"
        ))
        val deleteResult = executor.execute(deleteCall)
        assertTrue(deleteResult.isSuccess)
    }

    // --- TIMERS TOOL TESTS ---

    @Test
    fun testTimersStartQueryAndCancel() = runBlocking {
        // 1. Start a Timer
        val startCall = ToolCall("manage_timers", mapOf(
            "action" to "start",
            "duration_seconds" to 120.0,
            "label" to "Pizza Oven"
        ))
        val startResult = executor.execute(startCall)
        assertTrue(startResult.isSuccess)
        val startJson = JSONObject(startResult.content)
        val timerId = startJson.getString("id")

        // 2. Query Timer
        val queryCall = ToolCall("manage_timers", mapOf(
            "action" to "query",
            "label" to "Pizza"
        ))
        val queryResult = executor.execute(queryCall)
        assertTrue(queryResult.isSuccess)
        val queryJson = JSONObject(queryResult.content)
        assertEquals(1, queryJson.getInt("count"))

        // 3. Cancel Timer
        val cancelCall = ToolCall("manage_timers", mapOf(
            "action" to "cancel",
            "id" to timerId
        ))
        val cancelResult = executor.execute(cancelCall)
        assertTrue(cancelResult.isSuccess)
        val cancelJson = JSONObject(cancelResult.content)
        assertEquals(1, cancelJson.getInt("cancelled_count"))
    }

    // --- REMINDERS TOOL TESTS ---

    @Test
    fun testRemindersScheduling() = runBlocking {
        // 1. Create a Reminder (15 relative minutes in future)
        val createCall = ToolCall("manage_reminders", mapOf(
            "action" to "create",
            "id" to "rem-123",
            "title" to "Stretch and Walk",
            "time" to "15"
        ))
        val createResult = executor.execute(createCall)
        assertTrue(createResult.isSuccess)

        // 2. List Reminders
        val listCall = ToolCall("manage_reminders", mapOf("action" to "list"))
        val listResult = executor.execute(listCall)
        assertTrue(listResult.isSuccess)
        val listJson = JSONObject(listResult.content)
        assertTrue(listJson.getInt("count") >= 1)

        // 3. Retrieve Reminder
        val retrieveCall = ToolCall("manage_reminders", mapOf(
            "action" to "retrieve",
            "id" to "rem-123"
        ))
        val retrieveResult = executor.execute(retrieveCall)
        assertTrue(retrieveResult.isSuccess)

        // 4. Cancel/Delete Reminder
        val cancelCall = ToolCall("manage_reminders", mapOf(
            "action" to "cancel",
            "id" to "rem-123"
        ))
        val cancelResult = executor.execute(cancelCall)
        assertTrue(cancelResult.isSuccess)
    }

    @Test
    fun testRemindersPastTimeRejection() = runBlocking {
        // Attempting to schedule for a time that has already passed
        val createCall = ToolCall("manage_reminders", mapOf(
            "action" to "create",
            "id" to "rem-past",
            "title" to "Yesterday Task",
            "time" to "2020-01-01 12:00"
        ))
        val createResult = executor.execute(createCall)
        assertTrue(createResult.isError)
        assertNotNull(createResult.errorMessage)
        assertTrue(createResult.errorMessage!!.contains("must be in the future"))
    }

    // --- TOOL REGISTRATION TEST ---

    @Test
    fun testRegistrationOfProductivityTools() {
        assertTrue(registry.contains("manage_notes"))
        assertTrue(registry.contains("manage_tasks"))
        assertTrue(registry.contains("manage_timers"))
        assertTrue(registry.contains("manage_reminders"))
    }
}
