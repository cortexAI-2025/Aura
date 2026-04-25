package com.aura.feature.chat

import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.Message

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isThinking: Boolean = false,
    val streamingText: String = "",
    val pendingAction: AgentAction? = null,
    val modelStatus: ModelStatusUi = ModelStatusUi.CHECKING,
    val error: String? = null,
)

sealed interface ChatMessage {
    data class User(val message: Message) : ChatMessage
    data class Agent(val message: Message) : ChatMessage
    data class ActionLog(val description: String, val success: Boolean) : ChatMessage
    data object Thinking : ChatMessage
}

enum class ModelStatusUi { CHECKING, NOT_FOUND, LOADING, READY }
