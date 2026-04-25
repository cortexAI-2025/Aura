package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.GoalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE isActive = 1 ORDER BY priority DESC")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals ORDER BY priority DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET progress = :progress, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Float, nowMs: Long)

    @Query("UPDATE goals SET isActive = 0, updatedAtMs = :nowMs WHERE id = :id")
    suspend fun archive(id: String, nowMs: Long)
}
