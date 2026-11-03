package com.aura.core.domain.usecase

import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.GoalCategory
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class RetrieveContextUseCaseTest {

    private fun goal(i: Int) = Goal(id = "g$i", title = "t$i", description = "d", category = GoalCategory.OTHER)

    private fun memories() = mockk<MemoryRepository>().also {
        coEvery { it.search(any(), any(), any()) } returns emptyList()
    }

    @Test fun `does not hang when the goals flow never completes`() = runBlocking {
        val goals = mockk<GoalRepository>()
        // Same shape as a Room Flow: emits the current rows, then stays open forever.
        every { goals.observeActive() } returns flow { emit(listOf(goal(1))); awaitCancellation() }

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(memories(), goals).invoke(floatArrayOf(1f)) }

        assertEquals(listOf(goal(1)), ctx.activeGoals)
    }

    @Test fun `keeps at most five goals`() = runBlocking {
        val goals = mockk<GoalRepository>()
        every { goals.observeActive() } returns flow { emit((1..9).map(::goal)); awaitCancellation() }

        val ctx = withTimeout(5_000) { RetrieveContextUseCase(memories(), goals).invoke(floatArrayOf(1f)) }

        assertEquals(5, ctx.activeGoals.size)
    }
}
