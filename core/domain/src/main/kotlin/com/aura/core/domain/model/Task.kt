package com.aura.core.domain.model

import java.time.Duration
import java.time.Instant
import java.util.UUID

// ─── Public task API submitted by callers (UI, tests, proactive triggers) ─────

/**
 * A unit of work submitted to the Aura agent.
 *
 * Unlike [AgentTask] (internal queue entry), [Task] is the public-facing contract:
 * callers build a Task, [AuraAgent.submitTask] converts it to an [AgentTask] for
 * the [TaskQueue] and emits the [AuraLogger] TASK / ENQUEUED lines.
 */
data class Task(
    val id: String = UUID.randomUUID().toString().take(8),
    val description: String,
    val domain: Domain,
    val priority: TaskPriority = TaskPriority.NORMAL,
    /** Absolute deadline in epoch-ms; drives automatic CRITICAL upgrade. */
    val deadline: Long? = null,
)

enum class Domain { SOCIAL, WORK, FINANCE, HEALTH, PERSONAL, OTHER }

// ─── Priority (shared between public Task API and internal AgentTask) ─────────

enum class TaskPriority(val weight: Int) {
    /** Deadline < 2 h — preempts any currently running task. */
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3),
    BACKGROUND(4),
}

// ─── Internal queue entry ─────────────────────────────────────────────────────

enum class TaskSource { USER_MESSAGE, PROACTIVE_TRIGGER, BACKGROUND_SYNC }

data class AgentTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val payload: String,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val deadline: Instant? = null,
    val source: TaskSource = TaskSource.USER_MESSAGE,
    val createdAt: Instant = Instant.now(),
) {
    /**
     * Effective priority: auto-upgrade to CRITICAL if deadline is within 2 h,
     * regardless of the declared priority.
     */
    fun effectivePriority(): TaskPriority {
        if (deadline != null && Duration.between(Instant.now(), deadline).toHours() < 2) {
            return TaskPriority.CRITICAL
        }
        return priority
    }
}
