package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.MemoryEntity
import com.example.data.local.dao.FileDao
import com.example.data.local.entity.FileDocumentEntity
import com.example.data.local.entity.MemoryExtractionJobEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        MemoryExtractionJobEntity::class,
        FileDocumentEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryExtractionJobDao(): com.example.data.local.dao.MemoryExtractionJobDao
    abstract fun fileDao(): FileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add confidenceScore with default 3 (Confirmed)
                db.execSQL("ALTER TABLE memories ADD COLUMN confidenceScore INTEGER NOT NULL DEFAULT 3")
                
                // Add lastAccessedTimestamp with default 0
                db.execSQL("ALTER TABLE memories ADD COLUMN lastAccessedTimestamp INTEGER NOT NULL DEFAULT 0")
                // Copy existing lastUpdatedTimestamp values into lastAccessedTimestamp for V2 memories
                db.execSQL("UPDATE memories SET lastAccessedTimestamp = lastUpdatedTimestamp")
                
                // Add accessCount with default 0
                db.execSQL("ALTER TABLE memories ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
                
                // Add state with default 'CONFIRMED'
                db.execSQL("ALTER TABLE memories ADD COLUMN state TEXT NOT NULL DEFAULT 'CONFIRMED'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN temporalType TEXT NOT NULL DEFAULT 'PERSISTENT'")
                db.execSQL("ALTER TABLE memories ADD COLUMN expirationTimestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_extraction_jobs` (
                        `conversationId` TEXT NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `lastUpdatedTimestamp` INTEGER NOT NULL, 
                        `retryCount` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`conversationId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `file_documents` (
                        `id` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `localUri` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `extractionStatus` TEXT NOT NULL,
                        `extractedText` TEXT,
                        `contentHash` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_documents_contentHash` ON `file_documents` (`contentHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_documents_createdAt` ON `file_documents` (`createdAt`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aether_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
