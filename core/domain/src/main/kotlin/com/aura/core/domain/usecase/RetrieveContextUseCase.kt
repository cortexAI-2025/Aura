package com.aura.core.domain.usecase

import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.repository.MemoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
            // observeActive() is a Room Flow that never completes: take ONE snapshot.
            // (collect{} here suspended forever and froze the whole agent loop.)
            val goalsDeferred = async { goalRepository.observeActive().first().take(5) }
            AgentContext(memoriesDeferred.await(), goalsDeferred.await())
        }
}
