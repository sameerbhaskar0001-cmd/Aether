package com.example

import android.app.Application
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.repository.ConversationRepository
import com.example.data.repository.ConversationRepositoryImpl
import com.example.data.repository.MemoryRepository
import com.example.data.repository.MemoryRepositoryImpl
import com.example.data.repository.FileRepository
import com.example.data.repository.FileRepositoryImpl
import com.example.ai.file.search.FileSearchRepository
import com.example.ai.file.search.FileSearchRepositoryImpl
import com.example.util.ApiKeyStorage

class AetherApplication : Application() {

    companion object {
        lateinit var appContext: Application
            private set
    }

    lateinit var database: AppDatabase

    lateinit var conversationRepository: ConversationRepository

    lateinit var memoryRepository: MemoryRepository
    lateinit var fileRepository: FileRepository
    lateinit var fileSearchRepository: FileSearchRepository

    override fun onCreate() {
        super.onCreate()
        appContext = this

        // Runtime Verification Mechanism (Reports ONLY boolean presence, NEVER keys)
        val bcGemini = try { BuildConfig.GEMINI_API_KEY.isNotBlank() && !BuildConfig.GEMINI_API_KEY.startsWith("MY_") } catch (e: Throwable) { false }
        val bcGroq = try { BuildConfig.GROQ_API_KEY.isNotBlank() && !BuildConfig.GROQ_API_KEY.startsWith("MY_") } catch (e: Throwable) { false }
        val bcOpenRouter = try { BuildConfig.OPENROUTER_API_KEY.isNotBlank() && !BuildConfig.OPENROUTER_API_KEY.startsWith("MY_") } catch (e: Throwable) { false }

        val resGemini = ApiKeyStorage.getGeminiKey(this).isNotBlank()
        val resGroq = ApiKeyStorage.getGroqKey(this).isNotBlank()
        val resOpenRouter = ApiKeyStorage.getOpenRouterKey(this).isNotBlank()

        Log.i("AetherRuntimeVerify", "=== RUNTIME CONFIGURATION VERIFICATION ===")
        Log.i("AetherRuntimeVerify", "BuildConfig Gemini key present = $bcGemini")
        Log.i("AetherRuntimeVerify", "BuildConfig Groq key present = $bcGroq")
        Log.i("AetherRuntimeVerify", "BuildConfig OpenRouter key present = $bcOpenRouter")
        Log.i("AetherRuntimeVerify", "ApiKeyStorage resolved Gemini key present = $resGemini")
        Log.i("AetherRuntimeVerify", "ApiKeyStorage resolved Groq key present = $resGroq")
        Log.i("AetherRuntimeVerify", "ApiKeyStorage resolved OpenRouter key present = $resOpenRouter")
        Log.i("AetherRuntimeVerify", "==========================================")

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
