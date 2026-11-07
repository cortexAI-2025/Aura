package com.aura.agent.memory.logic

import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryType
import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    private fun mem(text: String) = Memory("id", text, FloatArray(0), MemoryType.SEMANTIC)

    @Test fun `precheck refuses empty, tiny, huge and secret texts`() {
        assertEquals(RejectReason.EMPTY, MemoryPolicy.precheck("   "))
        assertEquals(RejectReason.TOO_SHORT, MemoryPolicy.precheck("ok"))
        assertEquals(RejectReason.TOO_LONG, MemoryPolicy.precheck("a ".repeat(400)))
        assertEquals(RejectReason.SECRET, MemoryPolicy.precheck("ma clé api est sk-abcdefghijklmnop1234"))
        assertEquals(RejectReason.SECRET, MemoryPolicy.precheck("mon mot de passe est Tr0ub4dor&3"))
        assertEquals(RejectReason.SECRET, MemoryPolicy.precheck("carte 4111 1111 1111 1111"))
        assertNull(MemoryPolicy.precheck("J'aime le café sans sucre"))
    }

    @Test fun `no similar memory means accept`() {
        assertEquals(MemoryDecision.Accept(null), MemoryPolicy.decide("x", null, 0.0))
    }

    @Test fun `identical text is a duplicate whatever the score`() {
        val existing = mem("J'aime le café")
        assertTrue(MemoryPolicy.decide("j'aime le CAFÉ", existing, 0.1) is MemoryDecision.Duplicate)
    }

    @Test fun `high similarity merges, medium flags, low accepts`() {
        val e = mem("Ma ville est Paris")
        assertTrue(MemoryPolicy.decide("Ma ville est Paris !!", e, 0.9) is MemoryDecision.Duplicate)
        assertEquals(MemoryDecision.Accept(e), MemoryPolicy.decide("Ma ville est Lyon", e, 0.5))
        assertEquals(MemoryDecision.Accept(null), MemoryPolicy.decide("J'ai un chat", e, 0.1))
    }
}
