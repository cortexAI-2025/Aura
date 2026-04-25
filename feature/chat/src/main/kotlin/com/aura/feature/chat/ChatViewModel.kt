package com.aura.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.core.*
import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val conversationRepository: ConversationRepository,
    private val llmLoader: LLMLoader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
        observeServiceEvents()
        checkModelStatus()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            conversationRepository.observeMessages(30).collect { messages ->
                val chatMessages = messages.reversed().map { msg ->
                    if (msg.role == MessageRole.USER) ChatMessage.User(msg)
                    else ChatMessage.Agent(msg)
                }
                _uiState.update { it.copy(messages = chatMessages) }
            }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            AuraAgentService.agentEvents.collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(modelStatus = ModelStatusUi.CHECKING) }
            val status = llmLoader.getModelStatus()
            _uiState.update {
                it.copy(modelStatus = when (status) {
                    ModelStatus.LOADED -> ModelStatusUi.READY
                    ModelStatus.FOUND_NOT_LOADED -> ModelStatusUi.LOADING
                    ModelStatus.NOT_FOUND -> ModelStatusUi.NOT_FOUND
                })
            }
        }
    }

    fun onInputChanged(text: String) = _uiState.update { it.copy(inputText = text) }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        _uiState.update { it.copy(inputText = "", isThinking = true, streamingText = "") }
        AuraAgentService.processMessage(context, text)
    }

    fun confirmAction(action: AgentAction) {
        _uiState.update { it.copy(pendingAction = null) }
        // Re-process with confirmed flag — in production, pass confirmation token to service
        viewModelScope.launch(Dispatchers.IO) {
            agentEngine.process("CONFIRMED: ${action.tool} ${action.params}").collect { handleAgentEvent(it) }
        }
    }

    fun dismissPendingAction() = _uiState.update { it.copy(pendingAction = null, isThinking = false) }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> _uiState.update { it.copy(isThinking = true) }
            is AgentEvent.Token -> _uiState.update { it.copy(streamingText = it.streamingText + event.text) }
            is AgentEvent.ActionResult -> {
                val log = ChatMessage.ActionLog("${event.result.tool.name}: ${event.result.output}", event.result.success)
                _uiState.update { it.copy(messages = it.messages + log) }
            }
            is AgentEvent.ConfirmationRequired -> _uiState.update {
                it.copy(isThinking = false, pendingAction = event.action)
            }
            is AgentEvent.FinalAnswer -> {
                val msg = Message(UUID.randomUUID().toString(), MessageRole.ASSISTANT, event.text, Instant.now())
                _uiState.update { it.copy(isThinking = false, streamingText = "", error = null) }
            }
            is AgentEvent.Error -> _uiState.update { it.copy(isThinking = false, error = event.message) }
            else -> {}
        }
    }
}
