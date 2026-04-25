package com.aura.agent.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Long-running foreground service that keeps the AgentEngine alive.
 *
 * Why a foreground service?
 *   Android kills background processes after ~1 minute. Aura needs to:
 *   a) Receive proactive triggers (alarms, calendar changes, incoming messages).
 *   b) Complete multi-step reasoning that may take 10–30 seconds on-device.
 *   A foreground service with a persistent notification is the only reliable
 *   mechanism that survives Doze mode and battery optimisation.
 */
@AndroidEntryPoint
class AuraAgentService : LifecycleService() {

    @Inject lateinit var agentEngine: AgentEngine
    @Inject lateinit var conversationRepository: ConversationRepository
    @Inject lateinit var llmLoader: LLMLoader

    companion object {
        const val ACTION_PROCESS_MESSAGE = "com.aura.PROCESS_MESSAGE"
        const val EXTRA_MESSAGE = "extra_message"
        const val CHANNEL_ID = "aura_service"

        private val _agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
        val agentEvents: SharedFlow<AgentEvent> = _agentEvents

        fun processMessage(context: Context, message: String) {
            val intent = Intent(context, AuraAgentService::class.java).apply {
                action = ACTION_PROCESS_MESSAGE
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startForegroundService(intent)
        }
    }

    private var processingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification("Aura is ready"))
        lifecycleScope.launch(Dispatchers.IO) { llmLoader.loadIfNeeded() }
        Timber.i("AuraAgentService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PROCESS_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                handleMessage(message)
            }
        }
        return START_STICKY
    }

    private fun handleMessage(userText: String) {
        processingJob?.cancel()
        processingJob = lifecycleScope.launch(Dispatchers.IO) {
            // Persist user message
            conversationRepository.addMessage(
                Message(UUID.randomUUID().toString(), MessageRole.USER, userText, Instant.now())
            )
            updateNotification("Aura is thinking...")

            val responseBuilder = StringBuilder()
            agentEngine.process(userText).collect { event ->
                _agentEvents.emit(event)
                when (event) {
                    is AgentEvent.FinalAnswer -> {
                        responseBuilder.append(event.text)
                        conversationRepository.addMessage(
                            Message(UUID.randomUUID().toString(), MessageRole.ASSISTANT, event.text, Instant.now())
                        )
                        updateNotification("Aura is ready")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aura Agent", NotificationManager.IMPORTANCE_LOW)
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }
}
