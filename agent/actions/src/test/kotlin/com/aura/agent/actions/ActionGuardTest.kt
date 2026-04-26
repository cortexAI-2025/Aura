package com.aura.agent.actions

import com.aura.core.common.AuraLogDelegate
import com.aura.core.common.AuraLogger
import com.aura.core.domain.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ActionGuardTest {

    private lateinit var guard: ActionGuard

    /**
     * Rules with quiet hours disabled (start == end == 0) so tests are time-independent.
     * Override individual fields as needed per test.
     */
    private fun rulesNoQuiet(
        autonomyLevel: AutonomyLevel = AutonomyLevel.SUPERVISED,
        allowedTools: Set<ToolType> = ToolType.entries.toSet(),
        allowedContacts: Set<String> = emptySet(),
        budget: BudgetRule? = null,
        requireConfirmationForSend: Boolean = true,
    ) = UserRules(
        autonomyLevel = autonomyLevel,
        allowedTools = allowedTools,
        allowedContacts = allowedContacts,
        budget = budget,
        requireConfirmationForSend = requireConfirmationForSend,
        quietHoursStart = 0,
        quietHoursEnd = 0, // 0 == 0 → condition is (now >= 0 && now < 0) = never quiet
    )

    private fun action(
        tool: ToolType,
        params: Map<String, String> = emptyMap(),
        requiresConfirmation: Boolean = false,
    ) = AgentAction(UUID.randomUUID().toString(), tool, params, requiresConfirmation = requiresConfirmation)

    @Before fun setUp() {
        guard = ActionGuard()
        // Silence AuraLogger output during tests
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {}
        }
    }

    @After fun tearDown() {
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {}
        }
    }

    // ── Tool allowlist ────────────────────────────────────────────────────────

    @Test fun `blocks tool not in allowlist`() {
        val rules = rulesNoQuiet(allowedTools = setOf(ToolType.CALENDAR_READ))
        val decision = guard.evaluate(action(ToolType.MESSAGE_SEND), rules)
        assertTrue(decision is GuardDecision.Block)
        assertTrue((decision as GuardDecision.Block).reason.contains("not in your allowed tools"))
    }

    @Test fun `allows tool in allowlist`() {
        val rules = rulesNoQuiet(allowedTools = setOf(ToolType.CALENDAR_READ))
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ), rules)
        assertTrue(decision is GuardDecision.Allow)
    }

    // ── Budget ────────────────────────────────────────────────────────────────

    @Test fun `blocks action exceeding budget cap`() {
        val rules = rulesNoQuiet(
            autonomyLevel = AutonomyLevel.AUTONOMOUS,
            budget = BudgetRule(maxAmountCents = 1000, requireConfirmationAboveCents = 500),
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "1500")), rules)
        assertTrue(decision is GuardDecision.Block)
        assertTrue((decision as GuardDecision.Block).reason.contains("exceeds your budget cap"))
    }

    @Test fun `requires confirmation when amount is above threshold but within cap`() {
        val rules = rulesNoQuiet(
            autonomyLevel = AutonomyLevel.AUTONOMOUS,
            budget = BudgetRule(maxAmountCents = 5000, requireConfirmationAboveCents = 500),
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "800")), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    @Test fun `allows action below confirmation threshold`() {
        val rules = rulesNoQuiet(
            autonomyLevel = AutonomyLevel.AUTONOMOUS,
            budget = BudgetRule(maxAmountCents = 5000, requireConfirmationAboveCents = 500),
        )
        val decision = guard.evaluate(action(ToolType.WEB_BROWSE, mapOf("amount_cents" to "200")), rules)
        assertTrue(decision is GuardDecision.Allow)
    }

    // ── Allowed contacts fast-path ────────────────────────────────────────────

    @Test fun `allows MESSAGE_SEND immediately when recipient is in allowedContacts`() {
        // Even in SUPERVISED mode: pre-approved contact bypasses everything
        val rules = rulesNoQuiet(
            allowedContacts = setOf("Sophie"),
            autonomyLevel = AutonomyLevel.SUPERVISED,
        )
        val decision = guard.evaluate(
            action(ToolType.MESSAGE_SEND, mapOf("recipient" to "Sophie")),
            rules,
        )
        assertTrue(decision is GuardDecision.Allow)
        assertEquals("Sophie in allowedContacts", (decision as GuardDecision.Allow).reason)
    }

    @Test fun `requires confirmation for MESSAGE_SEND when recipient not in allowedContacts`() {
        val rules = rulesNoQuiet(
            allowedContacts = setOf("Sophie"),
            autonomyLevel = AutonomyLevel.SUPERVISED,
        )
        val decision = guard.evaluate(
            action(ToolType.MESSAGE_SEND, mapOf("recipient" to "Unknown")),
            rules,
        )
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    // ── SUPERVISED mode ───────────────────────────────────────────────────────

    @Test fun `requires confirmation for write actions in SUPERVISED mode`() {
        val rules = rulesNoQuiet(autonomyLevel = AutonomyLevel.SUPERVISED)
        val decision = guard.evaluate(action(ToolType.CALENDAR_WRITE), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    @Test fun `allows write actions in AUTONOMOUS mode`() {
        val rules = rulesNoQuiet(autonomyLevel = AutonomyLevel.AUTONOMOUS)
        val decision = guard.evaluate(action(ToolType.CALENDAR_WRITE), rules)
        assertTrue(decision is GuardDecision.Allow)
    }

    // ── Self-declared confirmation ────────────────────────────────────────────

    @Test fun `respects action's own requiresConfirmation flag`() {
        val rules = rulesNoQuiet(autonomyLevel = AutonomyLevel.AUTONOMOUS)
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ, requiresConfirmation = true), rules)
        assertTrue(decision is GuardDecision.RequireConfirmation)
    }

    // ── Read-only tools always allowed (no side effects) ─────────────────────

    @Test fun `allows CALENDAR_READ in SUPERVISED mode without confirmation`() {
        val rules = rulesNoQuiet(autonomyLevel = AutonomyLevel.SUPERVISED)
        val decision = guard.evaluate(action(ToolType.CALENDAR_READ), rules)
        assertTrue(decision is GuardDecision.Allow)
    }

    @Test fun `allows SCREEN_READ during quiet hours`() {
        // SCREEN_READ has no side effects → not blocked by quiet hours regardless
        val rules = UserRules(quietHoursStart = 0, quietHoursEnd = 23) // always quiet
        val decision = guard.evaluate(action(ToolType.SCREEN_READ), rules)
        assertTrue(decision is GuardDecision.Allow)
    }
}
