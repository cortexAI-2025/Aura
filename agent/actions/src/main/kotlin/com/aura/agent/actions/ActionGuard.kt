package com.aura.agent.actions

import com.aura.core.common.AuraLogger
import com.aura.core.common.AuraLogger.TAG_GUARD
import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.AutonomyLevel
import com.aura.core.domain.model.ToolType
import com.aura.core.domain.model.UserRules
import com.aura.core.domain.model.displayName
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-execution sandbox that evaluates every [AgentAction] against [UserRules]
 * before the action reaches [ActionExecutor].
 *
 * Decision hierarchy (first matching rule wins):
 *   1. Tool explicitly forbidden → Block
 *   2. Recipient in allowedContacts (MESSAGE_SEND) → Allow immediately (bypasses quiet hours)
 *   3. Quiet hours active + side-effectful tool → Block
 *   4. Budget exceeded → Block
 *   5. Amount requires confirmation → RequireConfirmation
 *   6. SUPERVISED mode + write/send tool → RequireConfirmation
 *   7. Action self-flags requiresConfirmation → RequireConfirmation
 *   8. → Allow
 */
@Singleton
class ActionGuard @Inject constructor() {

    fun evaluate(action: AgentAction, rules: UserRules): GuardDecision {
        val toolName = action.tool.displayName()

        // ── 1. Tool allowlist ────────────────────────────────────────────────
        if (action.tool !in rules.allowedTools) {
            AuraLogger.log(TAG_GUARD, "CHECK $toolName → BLOCKED (not in allowedTools)")
            return GuardDecision.Block("Tool ${action.tool} is not in your allowed tools list.")
        }

        // ── 2. Pre-approved contact fast-path (bypasses quiet hours) ─────────
        // Contacts in allowedContacts are trusted for autonomous messaging at any time.
        if (action.tool == ToolType.MESSAGE_SEND && rules.allowedContacts.isNotEmpty()) {
            val recipient = action.params["recipient"] ?: ""
            if (recipient in rules.allowedContacts) {
                val reason = "$recipient in allowedContacts"
                AuraLogger.log(TAG_GUARD, "CHECK $toolName → ALLOWED ($reason)")
                return GuardDecision.Allow(reason)
            }
        }

        // ── 3. Quiet hours ───────────────────────────────────────────────────
        if (action.tool.hasSideEffects() && isQuietHour(rules)) {
            AuraLogger.log(TAG_GUARD, "CHECK $toolName → BLOCKED (quiet hours)")
            return GuardDecision.Block(
                "Quiet hours active (${rules.quietHoursStart}h–${rules.quietHoursEnd}h). " +
                "Action ${action.tool} deferred until morning."
            )
        }

        // ── 4 & 5. Budget ────────────────────────────────────────────────────
        val budget = rules.budget
        if (budget != null && action.tool.isFinancial()) {
            val amountCents = action.params["amount_cents"]?.toLongOrNull() ?: 0L
            if (amountCents > budget.maxAmountCents) {
                AuraLogger.log(TAG_GUARD, "CHECK $toolName → BLOCKED (budget exceeded)")
                return GuardDecision.Block(
                    "Amount ${amountCents / 100.0} ${budget.currency} exceeds your budget cap " +
                    "of ${budget.maxAmountCents / 100.0} ${budget.currency}."
                )
            }
            if (amountCents > budget.requireConfirmationAboveCents) {
                AuraLogger.log(TAG_GUARD, "CHECK $toolName → CONFIRM (amount above threshold)")
                return GuardDecision.RequireConfirmation(
                    "Confirm payment of ${amountCents / 100.0} ${budget.currency}? " +
                    "(cap: ${budget.maxAmountCents / 100.0} ${budget.currency})"
                )
            }
        }

        // ── 6. SUPERVISED mode for write/send tools ──────────────────────────
        if (rules.autonomyLevel == AutonomyLevel.SUPERVISED && action.tool.isWriteAction()) {
            AuraLogger.log(TAG_GUARD, "CHECK $toolName → CONFIRM (supervised mode)")
            return GuardDecision.RequireConfirmation(
                "Supervised mode: confirm ${action.tool.humanName()}?"
            )
        }

        // ── 7. Action self-declared confirmation ─────────────────────────────
        if (action.requiresConfirmation) {
            AuraLogger.log(TAG_GUARD, "CHECK $toolName → CONFIRM (self-declared)")
            return GuardDecision.RequireConfirmation(
                "Aura wants to ${action.tool.humanName()}. Authorize?"
            )
        }

        AuraLogger.log(TAG_GUARD, "CHECK $toolName → ALLOWED")
        return GuardDecision.Allow()
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
    data class Allow(val reason: String = "") : GuardDecision

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
