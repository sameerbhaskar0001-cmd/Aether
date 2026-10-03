package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.repository.ConversationRepository
import com.example.data.repository.ConversationRepositoryImpl
import com.example.data.repository.MemoryRepository
import com.example.data.repository.MemoryRepositoryImpl
import com.example.data.repository.FileRepository
import com.example.data.repository.FileRepositoryImpl
import com.example.ai.file.search.FileSearchRepository
import com.example.ai.file.search.FileSearchRepositoryImpl

class AetherApplication : Application() {

    lateinit var database: AppDatabase

    lateinit var conversationRepository: ConversationRepository

    lateinit var memoryRepository: MemoryRepository
    lateinit var fileRepository: FileRepository
    lateinit var fileSearchRepository: FileSearchRepository

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        conversationRepository = ConversationRepositoryImpl(
            database.conversationDao(),
            database.messageDao()
        )
        memoryRepository = MemoryRepositoryImpl(
            database.memoryDao()
        )
        fileRepository = FileRepositoryImpl(database.fileDao())
        fileSearchRepository = FileSearchRepositoryImpl(fileRepository)
    }
}
