package com.aura.core.domain.model

import java.time.Instant

/** A single turn in the conversation. */
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, String> = emptyMap(),
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

/** An action the agent wants to execute. */
data class AgentAction(
    val id: String,
    val tool: ToolType,
    val params: Map<String, String>,
    val reasoning: String = "",
    val requiresConfirmation: Boolean = false,
)

/** Available tools the agent can invoke. */
enum class ToolType {
    CALENDAR_READ, CALENDAR_WRITE,
    MESSAGE_SEND, MESSAGE_READ,
    WEB_SEARCH, WEB_BROWSE,
    NOTIFICATION_SEND,
    PHONE_CALL,
    APP_OPEN,
    SCREEN_READ,
    REMINDER_SET,
    CONTACT_LOOKUP,
    SETTINGS_READ,
    MEMORY_STORE, MEMORY_RETRIEVE,
    GOAL_UPDATE,
    UNKNOWN,
}

/** Result of an agent action execution. */
data class ActionResult(
    val actionId: String,
    val tool: ToolType,
    val success: Boolean,
    val output: String,
    val timestamp: Instant = Instant.now(),
)

/** A step in the agent reasoning loop (ReAct pattern). */
data class AgentStep(
    val thought: String,
    val action: AgentAction?,
    val observation: String?,
    val isFinal: Boolean,
    val finalAnswer: String? = null,
)

/** Budget constraint for autonomous spending. */
data class BudgetRule(
    val maxAmountCents: Long,
    val currency: String = "EUR",
    val perTransactionLimitCents: Long = maxAmountCents,
    val requireConfirmationAboveCents: Long = 500,
)

/** User permission rules governing agent autonomy. */
data class UserRules(
    val budget: BudgetRule? = null,
    val allowedTools: Set<ToolType> = ToolType.entries.toSet(),
    val requireConfirmationForSend: Boolean = true,
    val quietHoursStart: Int = 22,
    val quietHoursEnd: Int = 8,
    val autonomyLevel: AutonomyLevel = AutonomyLevel.SUPERVISED,
)

enum class AutonomyLevel {
    /** Agent always asks before acting. */
    SUPERVISED,
    /** Agent acts autonomously within rules, notifies after. */
    AUTONOMOUS,
    /** Full autonomy, no notifications unless errors. */
    SILENT,
}
