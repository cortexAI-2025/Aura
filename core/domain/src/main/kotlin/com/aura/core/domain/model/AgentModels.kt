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

/** Short CamelCase name used in structured log lines (e.g. [Tool] CalendarCheck → OBS). */
fun ToolType.displayName(): String = when (this) {
    ToolType.CALENDAR_READ    -> "CalendarCheck"
    ToolType.CALENDAR_WRITE   -> "CalendarWrite"
    ToolType.MESSAGE_SEND     -> "MessageSend"
    ToolType.MESSAGE_READ     -> "MessageRead"
    ToolType.WEB_SEARCH       -> "WebSearch"
    ToolType.WEB_BROWSE       -> "WebBrowse"
    ToolType.NOTIFICATION_SEND -> "NotificationSend"
    ToolType.PHONE_CALL       -> "PhoneCall"
    ToolType.APP_OPEN         -> "AppOpen"
    ToolType.SCREEN_READ      -> "ScreenRead"
    ToolType.REMINDER_SET     -> "ReminderSet"
    ToolType.CONTACT_LOOKUP   -> "ContactLookup"
    ToolType.SETTINGS_READ    -> "SettingsRead"
    ToolType.MEMORY_STORE     -> "MemoryStore"
    ToolType.MEMORY_RETRIEVE  -> "MemoryRetrieve"
    ToolType.GOAL_UPDATE      -> "GoalUpdate"
    ToolType.UNKNOWN          -> "Unknown"
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
    /**
     * Contacts pre-approved for autonomous messaging without confirmation.
     * Empty set means Aura always asks before sending to any recipient.
     */
    val allowedContacts: Set<String> = emptySet(),
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
