package com.aura.agent.actions

import com.aura.core.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ActionGuardTest {

    private lateinit var guard: ActionGuard

    private fun action(
        tool: ToolType,
        params: Map<String, String> = emptyMap(),
        requiresConfirmation: Boolean = false,
    ) = AgentAction(UUID.randomUUID().toString(), tool, params, requiresConfirmation = requiresConfirmation)

    @Before fun setUp() { guard = ActionGuard() }

    // ── Tool allowlist ────────────────────────────────────────────────────────

    @Test fun `blocks tool not in allowlist`() {
        val rules = UserRules(allowedTools = setOf(ToolType.CALENDAR_READ))
        val decision = guard.evaluate(action(ToolType.MESSAGE_SEND), rules)
        assertTrue(decision is GuardDecision.Block)
        assertTrue((decision as GuardDecision.Block).reason.contains("not in your allowed tools"))
    }

    @Test fun `allows tool in allowlist`() {
        val rules = UserRules(allowedTools = setOf(ToolType.CALENDAR_READ))
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ), rules)
        assertEquals(GuardDecision.Allow, decision)
    }

    // ── Budget ────────────────────────────────────────────────────────────────

    @Test fun `blocks action exceeding budget cap`() {
        val rules = UserRules(
            budget = BudgetRule(maxAmountCents = 1000, requireConfirmationAboveCents = 500),
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "1500")), rules)
        assertTrue(decision is GuardDecision.Block)
        assertTrue((decision as GuardDecision.Block).reason.contains("exceeds your budget cap"))
    }

    @Test fun `requires confirmation when amount is above threshold but within cap`() {
        val rules = UserRules(
            budget = BudgetRule(maxAmountCents = 5000, requireConfirmationAboveCents = 500),
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "800")), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    @Test fun `allows action below confirmation threshold`() {
        val rules = UserRules(
            budget = BudgetRule(maxAmountCents = 5000, requireConfirmationAboveCents = 500),
            autonomyLevel = AutonomyLevel.AUTONOMOUS,
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "200")), rules)
        assertEquals(GuardDecision.Allow, decision)
    }

    // ── SUPERVISED mode ───────────────────────────────────────────────────────

    @Test fun `requires confirmation for write actions in SUPERVISED mode`() {
        val rules = UserRules(autonomyLevel = AutonomyLevel.SUPERVISED)
        val decision = guard.evaluate(action(ToolType.CALENDAR_WRITE), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    @Test fun `allows write actions in AUTONOMOUS mode`() {
        val rules = UserRules(autonomyLevel = AutonomyLevel.AUTONOMOUS)
        val decision = guard.evaluate(action(ToolType.CALENDAR_WRITE), rules)
        assertEquals(GuardDecision.Allow, decision)
    }

    // ── Self-declared confirmation ────────────────────────────────────────────

    @Test fun `respects action's own requiresConfirmation flag`() {
        val rules = UserRules(autonomyLevel = AutonomyLevel.AUTONOMOUS)
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ, requiresConfirmation = true), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    // ── Read-only tools always allowed (no side effects) ─────────────────────

    @Test fun `allows CALENDAR_READ in SUPERVISED mode without confirmation`() {
        val rules = UserRules(autonomyLevel = AutonomyLevel.SUPERVISED)
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ), rules)
        assertEquals(GuardDecision.Allow, decision)
    }

    @Test fun `allows SCREEN_READ during quiet hours`() {
        // SCREEN_READ has no side effects → not blocked by quiet hours
        val rules = UserRules(quietHoursStart = 0, quietHoursEnd = 23) // always quiet
        val decision = guard.evaluate(action(ToolType.SCREEN_READ), rules)
        assertEquals(GuardDecision.Allow, decision)
    }
}
