package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.FileDocumentEntity
import com.example.data.model.file.FileSearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileSearchRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LocalFileSearchRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalFileSearchRepository(db.fileDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testExactPhraseMatch() = runBlocking {
        db.fileDao().insertFile(FileDocumentEntity(
            "1", "Doc1", "text/plain", 100, "uri", 1, 1, "COMPLETED", "Hello world this is a test", "hash1"
        ))
        
        val results = repository.searchFiles(FileSearchQuery("Hello world"))
        assertEquals(1, results.size)
        assertEquals("1", results[0].fileId)
    }

    @Test
    fun testTokenMatch() = runBlocking {
        db.fileDao().insertFile(FileDocumentEntity(
            "1", "Doc1", "text/plain", 100, "uri", 1, 1, "COMPLETED", "apple banana", "hash1"
        ))
        
        val results = repository.searchFiles(FileSearchQuery("banana"))
        assertEquals(1, results.size)
        assertEquals("1", results[0].fileId)
    }

    @Test
    fun testIrrelevantQuery() = runBlocking {
        db.fileDao().insertFile(FileDocumentEntity(
            "1", "Doc1", "text/plain", 100, "uri", 1, 1, "COMPLETED", "apple banana", "hash1"
        ))
        
        val results = repository.searchFiles(FileSearchQuery("cherry"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun testSnippetBounds() = runBlocking {
        db.fileDao().insertFile(FileDocumentEntity(
            "1", "Doc1", "text/plain", 100, "uri", 1, 1, "COMPLETED", "This is a very long text that contains the word banana somewhere in the middle of it for testing snippet bounds", "hash1"
        ))
        
        val results = repository.searchFiles(FileSearchQuery("banana"))
        assertEquals(1, results.size)
        assertTrue(results[0].snippet.length <= 130) // 100 + "..."
    }

    @Test
    fun testResultLimits() = runBlocking {
        for (i in 1..5) {
            db.fileDao().insertFile(FileDocumentEntity(
                "$i", "Doc$i", "text/plain", 100, "uri$i", 1, 1, "COMPLETED", "apple", "hash$i"
            ))
        }
        
        val results = repository.searchFiles(FileSearchQuery("apple", maxResults = 3))
        assertEquals(3, results.size)
    }

    @Test
    fun testExcludeUnsupportedFiles() = runBlocking {
        db.fileDao().insertFile(FileDocumentEntity(
            "1", "Doc1", "text/plain", 100, "uri", 1, 1, "COMPLETED", "apple", "hash1"
        ))
        db.fileDao().insertFile(FileDocumentEntity(
            "2", "Doc2", "text/plain", 100, "uri", 1, 1, "PENDING", "apple", "hash2"
        ))
        db.fileDao().insertFile(FileDocumentEntity(
            "3", "Doc3", "text/plain", 100, "uri", 1, 1, "COMPLETED", "", "hash3"
        ))
        
        val results = repository.searchFiles(FileSearchQuery("apple"))
        assertEquals(1, results.size)
        assertEquals("1", results[0].fileId)
    }
}
