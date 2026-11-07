package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Token totals as summed by SQLite. */
data class UsageRow(val prompt: Long, val completion: Long, val requests: Int)

data class UsageByModelRow(val providerId: String?, val model: String?, val prompt: Long, val completion: Long, val requests: Int)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    // v0.1 queries over ALL messages (kept for compatibility)
    @Query("SELECT * FROM messages ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clear()

    // v0.2: per conversation
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecentIn(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentIn(conversationId: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countIn(conversationId: String): Int

    // consumption, as reported by the provider APIs
    @Query(
        "SELECT COALESCE(SUM(promptTokens), 0) AS prompt, COALESCE(SUM(completionTokens), 0) AS completion, " +
            "COUNT(promptTokens) AS requests FROM messages",
    )
    suspend fun usageAll(): UsageRow

    @Query(
        "SELECT COALESCE(SUM(promptTokens), 0) AS prompt, COALESCE(SUM(completionTokens), 0) AS completion, " +
            "COUNT(promptTokens) AS requests FROM messages WHERE conversationId = :conversationId",
    )
    suspend fun usageIn(conversationId: String): UsageRow

    @Query(
        "SELECT providerId AS providerId, model AS model, COALESCE(SUM(promptTokens), 0) AS prompt, " +
            "COALESCE(SUM(completionTokens), 0) AS completion, COUNT(promptTokens) AS requests " +
            "FROM messages WHERE promptTokens IS NOT NULL GROUP BY providerId, model",
    )
    fun observeUsageByModel(): Flow<List<UsageByModelRow>>
}
