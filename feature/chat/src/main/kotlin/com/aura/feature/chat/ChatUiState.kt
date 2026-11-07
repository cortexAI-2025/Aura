package com.aura.feature.chat

import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.Conversation
import com.aura.core.domain.model.Message

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isThinking: Boolean = false,
    val streamingText: String = "",
    val pendingAction: AgentAction? = null,
    val modelStatus: ModelStatusUi = ModelStatusUi.CHECKING,
    val error: String? = null,
    /** "Z.ai · glm-4.7-flash" in cloud mode, "Local" otherwise: the model that will answer. */
    val activeLabel: String = "",
    val ttsProblem: Boolean = false,
    val handsFree: HandsFreeState = HandsFreeState.OFF,
    val partialText: String = "",
    val speakReplies: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val currentConversationId: String = "",
    /** Non-fatal remark from the agent (long conversation trimmed, reply cut short). */
    val notice: String? = null,
    /** What was sent along with the question in cloud mode ("2 souvenirs, 1 objectif"). */
    val contextInfo: String? = null,
)

sealed interface ChatMessage {
    data class User(val message: Message) : ChatMessage
    data class Agent(val message: Message) : ChatMessage
    data class ActionLog(val description: String, val success: Boolean) : ChatMessage
    data object Thinking : ChatMessage
}

enum class ModelStatusUi { CHECKING, NOT_FOUND, LOADING, READY, ERROR, CLOUD }

/** Hands-free conversation loop: listen → think → speak → listen… */
enum class HandsFreeState { OFF, LISTENING, THINKING, SPEAKING }
