package com.aura.feature.chat

import com.aura.ai.engine.OpenRouterCatalog
import com.aura.ai.engine.OpenRouterModel
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
    val cloudEnabled: Boolean = false,
    val cloudModel: String = "",
    val hasApiKey: Boolean = false,
    val autoFallback: Boolean = true,
    val ttsProblem: Boolean = false,
    val speakReplies: Boolean = false,
    val freeModels: List<OpenRouterModel> = OpenRouterCatalog.FALLBACK,
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
)

sealed interface ChatMessage {
    data class User(val message: Message) : ChatMessage
    data class Agent(val message: Message) : ChatMessage
    data class ActionLog(val description: String, val success: Boolean) : ChatMessage
    data object Thinking : ChatMessage
}

enum class ModelStatusUi { CHECKING, NOT_FOUND, LOADING, READY, ERROR, CLOUD }
