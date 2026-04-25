package com.aura.core.domain.usecase

import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.repository.MemoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

data class AgentContext(
    val relevantMemories: List<MemoryResult>,
    val activeGoals: List<Goal>,
)

class RetrieveContextUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val goalRepository: GoalRepository,
) {
    suspend operator fun invoke(queryEmbedding: FloatArray, topK: Int = 8): AgentContext =
        coroutineScope {
            val memoriesDeferred = async { memoryRepository.search(queryEmbedding, topK) }
            val goalsDeferred = async {
                val allGoals = mutableListOf<Goal>()
                goalRepository.observeActive().collect { allGoals.addAll(it) }
                allGoals.take(5)
            }
            AgentContext(memoriesDeferred.await(), goalsDeferred.await())
        }
}
