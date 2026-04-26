package com.aura.agent.core

import com.aura.core.common.AuraLogger
import com.aura.core.common.AuraLogger.TAG_AGENT
import com.aura.core.domain.model.AgentTask
import com.aura.core.domain.model.Task
import com.aura.core.domain.model.TaskSource
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public façade for submitting tasks to the Aura agent.
 *
 * Callers (UI, tests, proactive triggers) build a [Task] and hand it here.
 * [AuraAgent] converts it to an [AgentTask] for the [TaskQueue], emitting the
 * canonical TASK / ENQUEUED log lines consumed by the integration test and logcat.
 */
@Singleton
class AuraAgent @Inject constructor(
    private val taskQueue: TaskQueue,
) {
    /**
     * Submit a [Task] for asynchronous execution.
     * Returns the internal task ID (matches the ENQUEUED log line).
     */
    fun submitTask(task: Task): String {
        AuraLogger.log(TAG_AGENT, "TASK: \"${task.description}\"")

        val agentTask = AgentTask(
            id = task.id,
            payload = task.description,
            priority = task.priority,
            deadline = task.deadline?.let { Instant.ofEpochMilli(it) },
            source = TaskSource.USER_MESSAGE,
        )

        taskQueue.enqueue(agentTask)
        AuraLogger.log(TAG_AGENT, "ENQUEUED (id=${agentTask.id}, priority=${task.priority})")
        return agentTask.id
    }
}
