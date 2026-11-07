package com.aura.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT id FROM conversations ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun mostRecentId(): String?

    @Query("UPDATE conversations SET title = :title, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun rename(id: String, title: String, nowMs: Long)

    @Query("UPDATE conversations SET updatedAtMs = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}
