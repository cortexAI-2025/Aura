package com.aura.agent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

// ─── Domain types ─────────────────────────────────────────────────────────────

enum class TaskPriority(val weight: Int) {
    /** Deadline < 2 h — preempts any currently running task. */
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3),
    BACKGROUND(4),
}

enum class TaskSource { USER_MESSAGE, PROACTIVE_TRIGGER, BACKGROUND_SYNC }

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
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

data class QueueSnapshot(
    val queueSize: Int,
    val isProcessing: Boolean,
    val currentTaskId: String?,
)

// ─── TaskQueue ────────────────────────────────────────────────────────────────

/**
 * Priority task queue with single-consumer guarantee and CRITICAL preemption.
 *
 * Design:
 *   - Incoming tasks are pushed onto an unlimited [Channel].
 *   - A drain coroutine reads the channel and inserts tasks into a [PriorityQueue].
 *   - [processTasks] is a suspend function that picks the highest-priority task
 *     and calls the provided [processor] block, one task at a time.
 *   - CRITICAL tasks cancel the running task and are picked up immediately at the
 *     next scheduling tick (≤ 50 ms).
 *
 * Thread-safety: all mutations go through the single-threaded [queueDispatcher].
 */
@Singleton
class TaskQueue @Inject constructor() {

    private val incoming = Channel<AgentTask>(Channel.UNLIMITED)

    private val heap = PriorityQueue<AgentTask>(
        compareBy({ it.effectivePriority().weight }, { it.createdAt })
    )

    private val _snapshot = MutableStateFlow(QueueSnapshot(0, false, null))
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    // Tracks the currently running task so CRITICAL tasks can preempt it.
    private val currentTaskRef = AtomicReference<AgentTask?>(null)
    private var currentJob = AtomicReference<Job?>(null)

    // Single-threaded dispatcher ensures heap mutations are race-free without locking.
    private val queueDispatcher = Dispatchers.Default.limitedParallelism(1)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Enqueue a task. Returns immediately; processing is asynchronous. */
    fun enqueue(task: AgentTask) {
        incoming.trySend(task)
            .also { Timber.d("TaskQueue enqueued [${task.effectivePriority()}] ${task.id}: ${task.payload.take(60)}") }
    }

    /**
     * Starts the consumer loop. Call once from [AuraAgentService.onCreate].
     * Suspends indefinitely until the [scope] is cancelled.
     *
     * [processor] is called with each dequeued [AgentTask] and must suspend until
     * the task is complete. It will be cancelled if a CRITICAL task preempts it.
     */
    suspend fun processTasks(scope: CoroutineScope, processor: suspend (AgentTask) -> Unit) {
        // Drain incoming → heap
        scope.launch(queueDispatcher) {
            for (task in incoming) {
                heap.add(task)
                publishSnapshot()
                Timber.d("TaskQueue heap size: ${heap.size}")
            }
        }

        // Single consumer loop
        while (scope.isActive) {
            val task = withContext(queueDispatcher) { heap.poll() }
            if (task == null) {
                delay(50)
                continue
            }

            Timber.i("TaskQueue processing [${task.effectivePriority()}] ${task.id}")
            currentTaskRef.set(task)
            publishSnapshot(isProcessing = true, currentTaskId = task.id)

            val job = scope.launch {
                try {
                    processor(task)
                } catch (e: CancellationException) {
                    Timber.i("TaskQueue task ${task.id} preempted by CRITICAL task")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "TaskQueue task ${task.id} failed")
                }
            }
            currentJob.set(job)

            // Poll for CRITICAL preemption while the task runs
            while (job.isActive) {
                delay(50)
                val hasCritical = withContext(queueDispatcher) {
                    heap.peek()?.effectivePriority() == TaskPriority.CRITICAL
                }
                if (hasCritical) {
                    Timber.w("CRITICAL task arrived — preempting ${task.id}")
                    job.cancel()
                    // Re-enqueue the preempted task at HIGH priority so it's not lost
                    enqueue(task.copy(priority = TaskPriority.HIGH))
                    break
                }
            }
            job.join()

            currentTaskRef.set(null)
            currentJob.set(null)
            publishSnapshot(isProcessing = false, currentTaskId = null)
        }
    }

    private fun publishSnapshot(isProcessing: Boolean = false, currentTaskId: String? = null) {
        _snapshot.value = QueueSnapshot(heap.size, isProcessing, currentTaskId)
    }
}
