package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: String): MemoryEntity?

    /** Fetch all memories for in-memory cosine search — use sparingly (paginate for large corpora). */
    @Query("SELECT * FROM memories WHERE (:type IS NULL OR type = :type) ORDER BY importance DESC LIMIT :limit")
    suspend fun getAll(type: String? = null, limit: Int = 5000): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY importance DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("UPDATE memories SET accessedAtMs = :nowMs, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touchMemory(id: String, nowMs: Long)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memories WHERE id IN (SELECT id FROM memories ORDER BY importance ASC, accessedAtMs ASC LIMIT :excess)")
    suspend fun pruneOldest(excess: Int)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}
