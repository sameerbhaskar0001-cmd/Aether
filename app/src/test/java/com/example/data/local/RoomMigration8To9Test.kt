package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.ProactiveCooldownEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigration8To9Test {

    private lateinit var context: Context
    private val dbName = "migration_8_9_test.db"
    private var openHelper: SupportSQLiteOpenHelper? = null
    private var appDatabase: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        appDatabase?.close()
        openHelper?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun testRealRoomMigrationVersion8To9PreservesAllEntities() = runBlocking {
        // Step 1: Create a real SQLite database at Version 8 with all 8 existing entity tables
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 1. conversations
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `conversations` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `title` TEXT NOT NULL,
                            `lastUpdated` INTEGER NOT NULL,
                            `isIncognito` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())

                    // 2. messages
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `messages` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `conversationId` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `sender` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `status` TEXT NOT NULL
                        )
                    """.trimIndent())

                    // 3. memories
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `memories` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `content` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `createdTimestamp` INTEGER NOT NULL,
                            `lastUpdatedTimestamp` INTEGER NOT NULL,
                            `sourceConversationId` TEXT,
                            `isArchived` INTEGER NOT NULL DEFAULT 0,
                            `confidenceScore` INTEGER NOT NULL DEFAULT 1,
                            `lastAccessedTimestamp` INTEGER NOT NULL DEFAULT 0,
                            `accessCount` INTEGER NOT NULL DEFAULT 0,
                            `state` TEXT NOT NULL DEFAULT 'CANDIDATE',
                            `temporalType` TEXT NOT NULL DEFAULT 'PERSISTENT',
                            `expirationTimestamp` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())

                    // 4. memory_extraction_jobs
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `memory_extraction_jobs` (
                            `conversationId` TEXT NOT NULL PRIMARY KEY,
                            `status` TEXT NOT NULL,
                            `lastUpdatedTimestamp` INTEGER NOT NULL,
                            `retryCount` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())

                    // 5. file_documents
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `file_documents` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `fileName` TEXT NOT NULL,
                            `mimeType` TEXT NOT NULL,
                            `fileSize` INTEGER NOT NULL,
                            `localUri` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `extractionStatus` TEXT NOT NULL,
                            `extractedText` TEXT,
                            `contentHash` TEXT NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_documents_contentHash` ON `file_documents` (`contentHash`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_documents_createdAt` ON `file_documents` (`createdAt`)")

                    // 6. notes
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `notes` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `title` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL
                        )
                    """.trimIndent())

                    // 7. tasks
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `tasks` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `title` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `isCompleted` INTEGER NOT NULL,
                            `dueDate` INTEGER,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL
                        )
                    """.trimIndent())

                    // 8. reminders
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `reminders` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `title` TEXT NOT NULL,
                            `triggerTime` INTEGER NOT NULL,
                            `isCompleted` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // No upgrade logic during initial setup
                }
            })
            .build()

        openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val v8Db = openHelper!!.writableDatabase
        assertEquals(8, v8Db.version)

        // Step 2: Seed realistic data across all 8 existing tables at version 8
        v8Db.execSQL(
            "INSERT INTO conversations (id, title, lastUpdated, isIncognito) VALUES ('conv_100', 'Project Planning', 1740000000000, 0)"
        )
        v8Db.execSQL(
            "INSERT INTO messages (id, conversationId, content, sender, timestamp, status) VALUES ('msg_101', 'conv_100', 'Let us design the architecture', 'USER', 1740000010000, 'SENT')"
        )
        v8Db.execSQL(
            "INSERT INTO memories (id, content, category, createdTimestamp, lastUpdatedTimestamp, sourceConversationId, isArchived, confidenceScore, state, temporalType, expirationTimestamp) VALUES ('mem_102', 'User prefers dark mode UI and Kotlin', 'Preferences', 1740000020000, 1740000020000, 'conv_100', 0, 5, 'CONFIRMED', 'PERSISTENT', 0)"
        )
        v8Db.execSQL(
            "INSERT INTO memory_extraction_jobs (conversationId, status, lastUpdatedTimestamp, retryCount) VALUES ('conv_100', 'COMPLETED', 1740000030000, 0)"
        )
        v8Db.execSQL(
            "INSERT INTO file_documents (id, fileName, mimeType, fileSize, localUri, createdAt, updatedAt, extractionStatus, extractedText, contentHash) VALUES ('file_103', 'architecture.md', 'text/markdown', 2048, 'file:///data/docs/arch.md', 1740000040000, 1740000040000, 'COMPLETED', 'Architecture specification', 'hash_abc123')"
        )
        v8Db.execSQL(
            "INSERT INTO notes (id, title, content, createdAt, updatedAt) VALUES ('note_104', 'Sprint Goals', 'Ship production-ready Aether', 1740000050000, 1740000050000)"
        )
        v8Db.execSQL(
            "INSERT INTO tasks (id, title, content, isCompleted, dueDate, createdAt, updatedAt) VALUES ('task_105', 'Room migration tests', 'Verify v8 to v9 integrity', 0, 1740100000000, 1740000060000, 1740000060000)"
        )
        v8Db.execSQL(
            "INSERT INTO reminders (id, title, triggerTime, isCompleted, createdAt) VALUES ('rem_106', 'Review security audit', 1740200000000, 0, 1740000070000)"
        )

        // Step 3: Execute MIGRATION_8_9
        AppDatabase.MIGRATION_8_9.migrate(v8Db)
        v8Db.version = 9
        assertEquals(9, v8Db.version)

        // Step 4: Verify all original rows in all 8 existing tables remain 100% intact
        // 1. conversations
        val convCursor = v8Db.query("SELECT id, title, lastUpdated, isIncognito FROM conversations WHERE id = 'conv_100'")
        assertTrue(convCursor.moveToFirst())
        assertEquals("conv_100", convCursor.getString(0))
        assertEquals("Project Planning", convCursor.getString(1))
        assertEquals(1740000000000L, convCursor.getLong(2))
        assertEquals(0, convCursor.getInt(3))
        convCursor.close()

        // 2. messages
        val msgCursor = v8Db.query("SELECT id, conversationId, content, sender, timestamp, status FROM messages WHERE id = 'msg_101'")
        assertTrue(msgCursor.moveToFirst())
        assertEquals("msg_101", msgCursor.getString(0))
        assertEquals("conv_100", msgCursor.getString(1))
        assertEquals("Let us design the architecture", msgCursor.getString(2))
        assertEquals("USER", msgCursor.getString(3))
        assertEquals(1740000010000L, msgCursor.getLong(4))
        assertEquals("SENT", msgCursor.getString(5))
        msgCursor.close()

        // 3. memories
        val memCursor = v8Db.query("SELECT id, content, category, confidenceScore, state FROM memories WHERE id = 'mem_102'")
        assertTrue(memCursor.moveToFirst())
        assertEquals("mem_102", memCursor.getString(0))
        assertEquals("User prefers dark mode UI and Kotlin", memCursor.getString(1))
        assertEquals("Preferences", memCursor.getString(2))
        assertEquals(5, memCursor.getInt(3))
        assertEquals("CONFIRMED", memCursor.getString(4))
        memCursor.close()

        // 4. memory_extraction_jobs
        val jobCursor = v8Db.query("SELECT conversationId, status, retryCount FROM memory_extraction_jobs WHERE conversationId = 'conv_100'")
        assertTrue(jobCursor.moveToFirst())
        assertEquals("conv_100", jobCursor.getString(0))
        assertEquals("COMPLETED", jobCursor.getString(1))
        assertEquals(0, jobCursor.getInt(2))
        jobCursor.close()

        // 5. file_documents
        val fileCursor = v8Db.query("SELECT id, fileName, mimeType, fileSize, contentHash FROM file_documents WHERE id = 'file_103'")
        assertTrue(fileCursor.moveToFirst())
        assertEquals("file_103", fileCursor.getString(0))
        assertEquals("architecture.md", fileCursor.getString(1))
        assertEquals("text/markdown", fileCursor.getString(2))
        assertEquals(2048L, fileCursor.getLong(3))
        assertEquals("hash_abc123", fileCursor.getString(4))
        fileCursor.close()

        // 6. notes
        val noteCursor = v8Db.query("SELECT id, title, content FROM notes WHERE id = 'note_104'")
        assertTrue(noteCursor.moveToFirst())
        assertEquals("note_104", noteCursor.getString(0))
        assertEquals("Sprint Goals", noteCursor.getString(1))
        assertEquals("Ship production-ready Aether", noteCursor.getString(2))
        noteCursor.close()

        // 7. tasks
        val taskCursor = v8Db.query("SELECT id, title, isCompleted, dueDate FROM tasks WHERE id = 'task_105'")
        assertTrue(taskCursor.moveToFirst())
        assertEquals("task_105", taskCursor.getString(0))
        assertEquals("Room migration tests", taskCursor.getString(1))
        assertEquals(0, taskCursor.getInt(2))
        assertEquals(1740100000000L, taskCursor.getLong(3))
        taskCursor.close()

        // 8. reminders
        val remCursor = v8Db.query("SELECT id, title, triggerTime, isCompleted FROM reminders WHERE id = 'rem_106'")
        assertTrue(remCursor.moveToFirst())
        assertEquals("rem_106", remCursor.getString(0))
        assertEquals("Review security audit", remCursor.getString(1))
        assertEquals(1740200000000L, remCursor.getLong(2))
        assertEquals(0, remCursor.getInt(3))
        remCursor.close()

        // Step 5: Verify new table proactive_cooldowns exists and works
        v8Db.execSQL(
            "INSERT INTO proactive_cooldowns (signalId, lastSurfacedTimestamp) VALUES ('signal_interview_99', 1750000000000)"
        )
        val cooldownCursor = v8Db.query("SELECT signalId, lastSurfacedTimestamp FROM proactive_cooldowns WHERE signalId = 'signal_interview_99'")
        assertTrue(cooldownCursor.moveToFirst())
        assertEquals("signal_interview_99", cooldownCursor.getString(0))
        assertEquals(1750000000000L, cooldownCursor.getLong(1))
        cooldownCursor.close()

        // Close raw SQLite helper before opening with Room
        v8Db.close()
        openHelper!!.close()

        // Step 6: Verify Room AppDatabase builds on top of the migrated database file without crashing or failing validation
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_8_9)
            .build()
        appDatabase = roomDb

        val loadedNotes = roomDb.noteDao().getAll()
        assertEquals(1, loadedNotes.size)
        assertEquals("Sprint Goals", loadedNotes[0].title)

        val loadedTasks = roomDb.taskDao().getAll()
        assertEquals(1, loadedTasks.size)
        assertEquals("Room migration tests", loadedTasks[0].title)

        val loadedReminders = roomDb.reminderDao().getAll()
        assertEquals(1, loadedReminders.size)
        assertEquals("Review security audit", loadedReminders[0].title)

        val loadedCooldown = roomDb.proactiveCooldownDao().getCooldown("signal_interview_99")
        assertNotNull(loadedCooldown)
        assertEquals(1750000000000L, loadedCooldown?.lastSurfacedTimestamp)

        // Insert new cooldown via Room DAO to verify write operations
        roomDb.proactiveCooldownDao().upsertCooldown(
            ProactiveCooldownEntity(signalId = "signal_new_via_room", lastSurfacedTimestamp = 1750000050000L)
        )
        val newlyInserted = roomDb.proactiveCooldownDao().getCooldown("signal_new_via_room")
        assertNotNull(newlyInserted)
        assertEquals(1750000050000L, newlyInserted?.lastSurfacedTimestamp)
    }
}
