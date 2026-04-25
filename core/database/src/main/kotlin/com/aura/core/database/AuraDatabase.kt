package com.aura.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aura.core.database.dao.*
import com.aura.core.database.entity.*

@Database(
    entities = [
        MessageEntity::class,
        MemoryEntity::class,
        GoalEntity::class,
        ActionLogEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeEdgeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun goalDao(): GoalDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): AuraDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AuraDatabase::class.java, "aura.db")
                    // Use Android Keystore-backed encryption in production via SQLCipher;
                    // here we rely on Android file-system encryption (API 26+).
                    .fallbackToDestructiveMigration()
            }
            return builder.build()
        }
    }
}
