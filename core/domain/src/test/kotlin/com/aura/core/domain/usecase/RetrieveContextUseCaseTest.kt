package com.aura.core.domain.usecase

import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.GoalCategory
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrieveContextUseCaseTest {

    private fun goal(i: Int) = Goal(id = "g$i", title = "t$i", description = "d", category = GoalCategory.OTHER)
    private fun hit() = MemoryResult(Memory("m1", "j'aime le café", FloatArray(0), MemoryType.SEMANTIC), 0.9f)

    private fun memories(result: List<MemoryResult> = emptyList()) = mockk<MemoryRepository>().also {
        coEvery { it.recall(any(), any()) } returns result
    }

    @Test fun `does not hang when the goals flow never completes`() = runBlocking {
        val goals = mockk<GoalRepository>()
        // Same shape as a Room Flow: emits the current rows, then stays open forever.
        every { goals.observeActive() } returns flow { emit(listOf(goal(1))); awaitCancellation() }

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(memories(), goals).invoke("bonjour") }

        // Compare ids: Goal.createdAt defaults to Instant.now(), so two instances are never equal.
        assertEquals(listOf("g1"), ctx.activeGoals.map { it.id })
    }

    @Test fun `keeps at most five goals`() = runBlocking {
        val goals = mockk<GoalRepository>()
        every { goals.observeActive() } returns flow { emit((1..9).map(::goal)); awaitCancellation() }

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(memories(), goals).invoke("x") }

        assertEquals(5, ctx.activeGoals.size)
    }

    @Test fun `recalls memories from the plain text query`() = runBlocking {
        val goals = mockk<GoalRepository>()
        every { goals.observeActive() } returns flow { emit(emptyList()); awaitCancellation() }
        val repo = memories(listOf(hit()))

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(repo, goals).invoke("quel café ?", topK = 3) }

        assertEquals(listOf("m1"), ctx.relevantMemories.map { it.memory.id })
        coVerify { repo.recall("quel café ?", 3) }
    }

    @Test fun `memory lookup is skipped when the user turned memory off`() = runBlocking {
        val goals = mockk<GoalRepository>()
        every { goals.observeActive() } returns flow { emit(emptyList()); awaitCancellation() }
        val repo = memories(listOf(hit()))

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(repo, goals).invoke("x", includeMemories = false) }

        assertTrue(ctx.relevantMemories.isEmpty())
        coVerify(exactly = 0) { repo.recall(any(), any()) }
    }
}
