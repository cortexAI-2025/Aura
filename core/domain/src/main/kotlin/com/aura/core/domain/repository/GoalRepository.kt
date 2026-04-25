package com.aura.core.domain.repository

import com.aura.core.domain.model.Goal
import kotlinx.coroutines.flow.Flow

interface GoalRepository {
    suspend fun upsert(goal: Goal)
    suspend fun get(id: String): Goal?
    fun observeActive(): Flow<List<Goal>>
    fun observeAll(): Flow<List<Goal>>
    suspend fun updateProgress(id: String, progress: Float)
    suspend fun archive(id: String)
}
