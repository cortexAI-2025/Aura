package com.aura.ai.engine

import com.aura.ai.brain.GemmaPrompt
import com.aura.ai.provider.ChatEvent
import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.FinishReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Abstraction over the language model backend: the on-device model ([MediaPipeLLM]) or a cloud
 * provider ([CloudLLMEngine]). The agent talks to it through [chat] only.
 */
interface LLMEngine {
    val isReady: Boolean

    /** Load the on-device model from [modelPath]. No-op for cloud backends. */
    suspend fun load(modelPath: String)

    /** Raw prompt streaming (on-device template already applied). Kept for the local backend. */
    fun generate(prompt: String, maxTokens: Int = 1024): Flow<LLMToken>

    suspend fun generateFull(prompt: String, maxTokens: Int = 1024): String

    /**
     * Structured chat: the one entry point the agent uses. The default renders the Gemma template and streams
     * [generate]; cloud backends override it with the real provider call (streaming, usage, retries).
     * Failures surface as [com.aura.ai.provider.ProviderException] (cloud) or other exceptions (local).
     */
    fun chat(messages: List<ChatMessage>, maxTokens: Int = 1024, thinking: Boolean = false): Flow<ChatEvent> = flow {
        generate(GemmaPrompt.format(messages), maxTokens).collect { if (it.text.isNotEmpty()) emit(ChatEvent.Delta(it.text)) }
        emit(ChatEvent.Finished(FinishReason.STOP, null, "local"))
    }

    /** Embedding for semantic memory (always computed on-device, whatever the chat backend). */
    suspend fun embed(text: String): FloatArray

    fun release()
}

data class LLMToken(val text: String, val isDone: Boolean)
