package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.ActionLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ActionLogEntity)

    @Query("SELECT * FROM action_log ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActionLogEntity>>
}
