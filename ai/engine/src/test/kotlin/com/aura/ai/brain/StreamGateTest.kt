package com.aura.ai.brain

import org.junit.Assert.*
import org.junit.Test

class StreamGateTest {
    private fun feed(vararg parts: String): String { val g = StreamGate(); return parts.joinToString("") { g.accept(it) } }

    @Test fun `plain text streams through, including text buffered before the decision`() {
        assertEquals("Bonjour, ça va ?", feed("  ", "Bon", "jour, ", "ça va ?"))
    }

    @Test fun `a json tool call is never shown`() {
        assertEquals("", feed("{", "\"action\":", "{\"tool\":\"X\"}}"))
        assertEquals("", feed("  \n{\"action\":{}}"))
    }

    @Test fun `a json code fence is structured but a normal code block is shown`() {
        assertEquals("", feed("```", "json\n{\"action\":{}}\n```"))
        assertEquals("```kotlin\nval x = 1\n```", feed("```", "kotlin\nval x = 1\n```"))
    }

    @Test fun `structured flag is exposed`() {
        val g = StreamGate(); g.accept("{")
        assertTrue(g.isStructured)
        val h = StreamGate(); h.accept("Salut")
        assertFalse(h.isStructured)
    }
}
