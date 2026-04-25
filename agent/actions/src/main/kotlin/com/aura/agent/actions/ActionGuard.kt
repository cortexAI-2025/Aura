package com.aura.agent.actions

import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.AutonomyLevel
import com.aura.core.domain.model.ToolType
import com.aura.core.domain.model.UserRules
import timber.log.Timber
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-execution sandbox that evaluates every [AgentAction] against [UserRules]
 * before the action reaches [ActionExecutor].
 *
 * Decision hierarchy (first matching rule wins):
 *   1. Tool explicitly forbidden → Block
 *   2. Quiet hours active + side-effectful tool → Block
 *   3. Budget exceeded → Block
 *   4. Amount requires confirmation → RequireConfirmation
 *   5. SUPERVISED mode + write/send tool → RequireConfirmation
 *   6. Action self-flags requiresConfirmation → RequireConfirmation
 *   7. → Allow
 *
 * All decisions are logged with their reason so the user can audit Aura's
 * behaviour history from the timeline screen.
 */
@Singleton
class ActionGuard @Inject constructor() {

    fun evaluate(action: AgentAction, rules: UserRules): GuardDecision {
        // ── 1. Tool allowlist ────────────────────────────────────────────────
        if (action.tool !in rules.allowedTools) {
            return GuardDecision.Block("Tool ${action.tool} is not in your allowed tools list.")
                .also { Timber.w("Guard BLOCKED ${action.tool}: not allowed") }
        }

        // ── 2. Quiet hours ───────────────────────────────────────────────────
        if (action.tool.hasSideEffects() && isQuietHour(rules)) {
            return GuardDecision.Block(
                "Quiet hours active (${rules.quietHoursStart}h–${rules.quietHoursEnd}h). " +
                "Action ${action.tool} deferred until morning."
            ).also { Timber.i("Guard BLOCKED ${action.tool}: quiet hours") }
        }

        // ── 3 & 4. Budget ────────────────────────────────────────────────────
        val budget = rules.budget
        if (budget != null && action.tool.isFinancial()) {
            val amountCents = action.params["amount_cents"]?.toLongOrNull() ?: 0L
            if (amountCents > budget.maxAmountCents) {
                return GuardDecision.Block(
                    "Amount ${amountCents / 100.0} ${budget.currency} exceeds your budget cap " +
                    "of ${budget.maxAmountCents / 100.0} ${budget.currency}."
                ).also { Timber.w("Guard BLOCKED ${action.tool}: budget exceeded") }
            }
            if (amountCents > budget.requireConfirmationAboveCents) {
                return GuardDecision.RequireConfirmation(
                    "Confirm payment of ${amountCents / 100.0} ${budget.currency}? " +
                    "(cap: ${budget.maxAmountCents / 100.0} ${budget.currency})"
                ).also { Timber.i("Guard CONFIRM ${action.tool}: amount above confirmation threshold") }
            }
        }

        // ── 5. SUPERVISED mode for write/send tools ──────────────────────────
        if (rules.autonomyLevel == AutonomyLevel.SUPERVISED && action.tool.isWriteAction()) {
            return GuardDecision.RequireConfirmation(
                "Supervised mode: confirm ${action.tool.humanName()}?"
            ).also { Timber.i("Guard CONFIRM ${action.tool}: supervised mode") }
        }

        // ── 6. Action self-declared confirmation ─────────────────────────────
        if (action.requiresConfirmation) {
            return GuardDecision.RequireConfirmation(
                "Aura wants to ${action.tool.humanName()}. Authorize?"
            ).also { Timber.i("Guard CONFIRM ${action.tool}: self-declared") }
        }

        Timber.d("Guard ALLOWED ${action.tool}")
        return GuardDecision.Allow
    }

    private fun isQuietHour(rules: UserRules): Boolean {
        val now = LocalTime.now().hour
        return if (rules.quietHoursStart > rules.quietHoursEnd) {
            // Wraps midnight e.g. 22–8
            now >= rules.quietHoursStart || now < rules.quietHoursEnd
        } else {
            now >= rules.quietHoursStart && now < rules.quietHoursEnd
        }
    }
}

// ─── Guard decision ───────────────────────────────────────────────────────────

sealed interface GuardDecision {
    /** Action is safe to execute immediately. */
    data object Allow : GuardDecision

    /** Action violates a hard rule — do not execute, report reason to user. */
    data class Block(val reason: String) : GuardDecision

    /** Action is within rules but requires explicit user confirmation. */
    data class RequireConfirmation(val reason: String) : GuardDecision
}

// ─── ToolType extension helpers ───────────────────────────────────────────────

/** Tools that create side-effects visible outside the device. */
private fun ToolType.hasSideEffects(): Boolean = this in setOf(
    ToolType.MESSAGE_SEND, ToolType.PHONE_CALL,
    ToolType.CALENDAR_WRITE, ToolType.NOTIFICATION_SEND,
)

/** Tools that involve spending money. */
private fun ToolType.isFinancial(): Boolean = this in setOf(
    ToolType.WEB_BROWSE, // may trigger purchase flows
)

/** Tools that mutate external state (write, send, call). */
private fun ToolType.isWriteAction(): Boolean = this in setOf(
    ToolType.CALENDAR_WRITE, ToolType.MESSAGE_SEND,
    ToolType.PHONE_CALL, ToolType.NOTIFICATION_SEND,
    ToolType.GOAL_UPDATE, ToolType.REMINDER_SET,
)

/** Human-readable action description for confirmation dialogs. */
fun ToolType.humanName(): String = when (this) {
    ToolType.CALENDAR_WRITE -> "create a calendar event"
    ToolType.MESSAGE_SEND -> "send a message"
    ToolType.PHONE_CALL -> "make a phone call"
    ToolType.NOTIFICATION_SEND -> "send a notification"
    ToolType.REMINDER_SET -> "set a reminder"
    ToolType.GOAL_UPDATE -> "update your goals"
    else -> name.lowercase().replace('_', ' ')
}
