package com.aura.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aura.core.domain.model.AgentTask
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.model.TaskPriority
import com.aura.core.domain.model.TaskSource
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Long-running foreground service that keeps the AgentEngine alive.
 *
 * Replaces the previous single [Job] approach with [TaskQueue]:
 *   - Tasks are processed one at a time in priority order.
 *   - CRITICAL tasks (deadline < 2 h) preempt any currently running task.
 *   - The service never drops a task — preempted tasks are re-enqueued at HIGH.
 */
/** An [AgentEvent] and the conversation it belongs to. */
data class TaggedEvent(val conversationId: String, val event: AgentEvent)

@AndroidEntryPoint
class AuraAgentService : LifecycleService() {

    @Inject lateinit var agentEngine: AgentEngine
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var llmLoader: LLMLoader
    @Inject lateinit var taskQueue: TaskQueue

    companion object {
        const val ACTION_PROCESS_MESSAGE = "com.aura.PROCESS_MESSAGE"
        const val ACTION_PROACTIVE_TRIGGER = "com.aura.PROACTIVE_TRIGGER"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PRIORITY = "extra_priority"
        const val EXTRA_DEADLINE_MS = "extra_deadline_ms"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_VOICE_MODE = "extra_voice_mode"
        const val CHANNEL_ID = "aura_service"
        /** A task that produces no final answer within this time is abandoned so the queue can't jam. */
        const val TASK_TIMEOUT_MS = 300_000L

        private val _agentEvents = MutableSharedFlow<TaggedEvent>(extraBufferCapacity = 64)

        /** Every event carries the conversation it belongs to: the UI shows only the open one. */
        val agentEvents: SharedFlow<TaggedEvent> = _agentEvents

        fun processMessage(
            context: Context,
            message: String,
            priority: TaskPriority = TaskPriority.NORMAL,
            deadlineMs: Long? = null,
            conversationId: String? = null,
            voiceMode: Boolean = false,
        ) {
            context.startForegroundService(
                Intent(context, AuraAgentService::class.java).apply {
                    action = ACTION_PROCESS_MESSAGE
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_PRIORITY, priority.name)
                    deadlineMs?.let { putExtra(EXTRA_DEADLINE_MS, it) }
                    conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
                    putExtra(EXTRA_VOICE_MODE, voiceMode)
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification("Aura is ready"))
        lifecycleScope.launch(Dispatchers.IO) { llmLoader.loadIfNeeded() }
        startQueueConsumer()
        observeQueueSnapshot()
        Timber.i("AuraAgentService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PROCESS_MESSAGE -> {
                val text = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                val priority = intent.getStringExtra(EXTRA_PRIORITY)
                    ?.let { runCatching { TaskPriority.valueOf(it) }.getOrDefault(TaskPriority.NORMAL) }
                    ?: TaskPriority.NORMAL
                val deadlineMs = if (intent.hasExtra(EXTRA_DEADLINE_MS)) intent.getLongExtra(EXTRA_DEADLINE_MS, 0) else null
                enqueueMessage(
                    text, priority, deadlineMs,
                    conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID),
                    voiceMode = intent.getBooleanExtra(EXTRA_VOICE_MODE, false),
                )
            }
            ACTION_PROACTIVE_TRIGGER -> {
                val text = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                enqueueMessage(text, TaskPriority.LOW, source = TaskSource.PROACTIVE_TRIGGER)
            }
        }
        return START_STICKY
    }

    private fun enqueueMessage(
        text: String,
        priority: TaskPriority,
        deadlineMs: Long? = null,
        source: TaskSource = TaskSource.USER_MESSAGE,
        conversationId: String? = null,
        voiceMode: Boolean = false,
    ) {
        taskQueue.enqueue(
            AgentTask(
                payload = text,
                conversationId = conversationId,
                voiceMode = voiceMode,
                priority = priority,
                deadline = deadlineMs?.let { Instant.ofEpochMilli(it) },
                source = source,
            )
        )
    }

    private fun startQueueConsumer() {
        lifecycleScope.launch(Dispatchers.IO) {
            taskQueue.processTasks(this) { task ->
                processTask(task)
            }
        }
    }

    private fun observeQueueSnapshot() {
        lifecycleScope.launch {
            taskQueue.snapshot.collect { snap ->
                if (snap.isProcessing) updateNotification("Aura is thinking… (${snap.queueSize} queued)")
                else if (snap.queueSize > 0) updateNotification("${snap.queueSize} task(s) pending")
                else updateNotification("Aura is ready")
            }
        }
    }

    private suspend fun processTask(task: AgentTask) {
        Timber.i("Processing task (${task.payload.length} chars)")
        val convId = task.conversationId ?: conversationRepository.currentConversationId()
        fun emitTagged(e: AgentEvent) = _agentEvents.tryEmit(TaggedEvent(convId, e))

        conversationRepository.addMessage(
            convId, Message(UUID.randomUUID().toString(), MessageRole.USER, task.payload, Instant.now())
        )

        val finished = withTimeoutOrNull(TASK_TIMEOUT_MS) {
            agentEngine.process(task.payload, convId, task.voiceMode).collect { event ->
                if (event is AgentEvent.FinalAnswer) {
                    // Persist first so the UI, which reacts to the event, finds the message in the database.
                    conversationRepository.addMessage(
                        convId,
                        Message(
                            id = UUID.randomUUID().toString(), role = MessageRole.ASSISTANT, content = event.text,
                            timestamp = Instant.now(), providerId = event.providerId, model = event.model,
                            promptTokens = event.usage?.promptTokens, completionTokens = event.usage?.completionTokens,
                            finishReason = if (event.truncated) "truncated" else null,
                        ),
                    )
                }
                _agentEvents.emit(TaggedEvent(convId, event))
            }
            true
        }
        if (finished == null) {
            emitTagged(AgentEvent.Error("Délai dépassé : aucune réponse du modèle après ${TASK_TIMEOUT_MS / 60_000} minutes."))
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aura Agent", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, buildNotification(text))
    }
}
