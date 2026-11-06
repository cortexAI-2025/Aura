package com.aura.ai.brain

import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRole.*
import org.junit.Assert.*
import org.junit.Test

class ContextWindowTest {
    private fun msg(role: com.aura.ai.provider.ChatRole, n: Int, tag: String = "") = ChatMessage(role, tag + "x".repeat(n))

    @Test fun `everything fits - nothing changes`() {
        val m = listOf(msg(SYSTEM, 30), msg(USER, 30), msg(ASSISTANT, 30), msg(USER, 30))
        val fit = ContextWindow.fit(m, 1_000)
        assertEquals(m, fit.messages)
        assertEquals(0, fit.droppedMessages)
        assertFalse(fit.truncated)
    }

    @Test fun `oldest turns are dropped first and the last message always stays`() {
        val m = listOf(msg(SYSTEM, 30, "S"), msg(USER, 300, "A"), msg(ASSISTANT, 300, "B"), msg(USER, 300, "C"), msg(ASSISTANT, 300, "D"), msg(USER, 30, "LAST"))
        val fit = ContextWindow.fit(m, 250) // room for system + last + ~2 turns
        assertTrue(fit.messages.first().content.startsWith("S"))
        assertTrue(fit.messages.last().content.startsWith("LAST"))
        assertTrue(fit.droppedMessages >= 2)
        assertFalse("oldest is gone", fit.messages.any { it.content.startsWith("A") })
    }

    @Test fun `history never starts with an assistant turn after trimming`() {
        val m = listOf(msg(SYSTEM, 10), msg(USER, 600, "U1"), msg(ASSISTANT, 30, "A1"), msg(USER, 30, "LAST"))
        val fit = ContextWindow.fit(m, 100)
        val firstHistory = fit.messages.drop(1).first()
        assertNotEquals(ASSISTANT, firstHistory.role)
    }

    @Test fun `an oversized last message is truncated rather than dropped`() {
        val m = listOf(msg(SYSTEM, 30), msg(USER, 30_000))
        val fit = ContextWindow.fit(m, 500)
        assertTrue(fit.truncated)
        assertTrue(fit.messages.last().content.endsWith("[tronqué]"))
        assertTrue(ContextWindow.estimateTokens(fit.messages.last().content) < 700)
    }

    @Test fun `an oversized system prompt cannot starve the conversation`() {
        val m = listOf(msg(SYSTEM, 30_000), msg(USER, 60))
        val fit = ContextWindow.fit(m, 1_000)
        assertTrue(fit.truncated)
        assertTrue(ContextWindow.estimateTokens(fit.messages.first().content) <= 700)
        assertEquals(msg(USER, 60), fit.messages.last())
    }

    @Test fun `empty and system-only inputs are safe`() {
        assertEquals(0, ContextWindow.fit(emptyList(), 100).messages.size)
        val sysOnly = listOf(msg(SYSTEM, 10))
        assertEquals(sysOnly, ContextWindow.fit(sysOnly, 100).messages)
    }
}
