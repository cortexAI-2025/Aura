package com.aura.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.core.database.dao.*
import com.aura.core.database.entity.*

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        GoalEntity::class,
        ActionLogEntity::class,
        KnowledgeNodeEntity::class,
        KnowledgeEdgeEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun goalDao(): GoalDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        /**
         * v0.1 -> v0.2. Nothing is lost: the single v0.1 history becomes the conversation "legacy",
         * existing memories keep their text and are marked as automatic when they were episodic.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))")

                db.execSQL("ALTER TABLE `messages` ADD COLUMN `conversationId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `providerId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `model` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `promptTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `completionTokens` INTEGER")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `finishReason` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
                db.execSQL(
                    "INSERT INTO `conversations` (`id`, `title`, `createdAtMs`, `updatedAtMs`) " +
                        "SELECT 'legacy', 'Conversation précédente', `lo`, `hi` FROM (SELECT MIN(`timestampMs`) AS `lo`, MAX(`timestampMs`) AS `hi`, COUNT(*) AS `n` FROM `messages`) WHERE `n` > 0",
                )
                db.execSQL("UPDATE `messages` SET `conversationId` = 'legacy' WHERE `conversationId` = ''")

                db.execSQL("ALTER TABLE `memories` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'USER'")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `needsReview` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `relatedId` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `updatedAtMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `embeddingKind` TEXT NOT NULL DEFAULT 'stub'")
                db.execSQL("UPDATE `memories` SET `source` = 'AUTO' WHERE `type` = 'EPISODIC'")
                db.execSQL("UPDATE `memories` SET `updatedAtMs` = `createdAtMs`")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): AuraDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AuraDatabase::class.java, "aura.db")
                    .addMigrations(MIGRATION_1_2)
                    // Only a downgrade may reset data; an upgrade must always go through a real migration.
                    .fallbackToDestructiveMigrationOnDowngrade()
            }
            return builder.build()
        }
    }
}
