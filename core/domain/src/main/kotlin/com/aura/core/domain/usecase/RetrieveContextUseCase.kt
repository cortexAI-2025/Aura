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
    /**
     * @param includeMemories false skips the memory lookup entirely (user turned memory off).
     */
    suspend operator fun invoke(query: String, topK: Int = 8, includeMemories: Boolean = true): AgentContext =
        coroutineScope {
            val memoriesDeferred = async {
                if (includeMemories) memoryRepository.recall(query, topK) else emptyList()
            }
            // observeActive() is a Room Flow that never completes: take ONE snapshot.
            val goalsDeferred = async { goalRepository.observeActive().first().take(5) }
            AgentContext(memoriesDeferred.await(), goalsDeferred.await())
        }
}
