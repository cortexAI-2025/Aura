package com.aura.core.data.repository

import com.aura.core.database.dao.GoalDao
import com.aura.core.database.entity.GoalEntity
import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.GoalCategory
import com.aura.core.domain.repository.GoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class GoalRepositoryImpl @Inject constructor(
    private val goalDao: GoalDao,
) : GoalRepository {

    override suspend fun upsert(goal: Goal) = goalDao.upsert(goal.toEntity())
    override suspend fun get(id: String) = goalDao.getById(id)?.toDomain()
    override fun observeActive(): Flow<List<Goal>> = goalDao.observeActive().map { it.map { e -> e.toDomain() } }
    override fun observeAll(): Flow<List<Goal>> = goalDao.observeAll().map { it.map { e -> e.toDomain() } }
    override suspend fun updateProgress(id: String, progress: Float) =
        goalDao.updateProgress(id, progress, Instant.now().toEpochMilli())
    override suspend fun archive(id: String) = goalDao.archive(id, Instant.now().toEpochMilli())
}

private fun Goal.toEntity() = GoalEntity(
    id = id, title = title, description = description, category = category.name,
    priority = priority, deadlineMs = deadline?.toEpochMilli(), progress = progress,
    isActive = isActive, createdAtMs = createdAt.toEpochMilli(), updatedAtMs = updatedAt.toEpochMilli(),
)

private fun GoalEntity.toDomain() = Goal(
    id = id, title = title, description = description,
    category = runCatching { GoalCategory.valueOf(category) }.getOrDefault(GoalCategory.OTHER),
    priority = priority, deadline = deadlineMs?.let { Instant.ofEpochMilli(it) },
    progress = progress, isActive = isActive,
    createdAt = Instant.ofEpochMilli(createdAtMs), updatedAt = Instant.ofEpochMilli(updatedAtMs),
)
