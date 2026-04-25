package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clear()
}
