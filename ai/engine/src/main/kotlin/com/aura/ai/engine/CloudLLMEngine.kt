package com.aura.ai.engine

import com.aura.ai.provider.ChatEvent
import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRequest
import com.aura.ai.provider.ChatRole
import com.aura.ai.provider.ErrorKind
import com.aura.ai.provider.ProviderError
import com.aura.ai.provider.ProviderException
import com.aura.ai.provider.ResilientChat
import com.aura.ai.settings.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud backend: the provider chosen in [AiSettings] with its own key, model and endpoint.
 * Embeddings stay on-device. Nothing here is specific to a vendor.
 */
@Singleton
class CloudLLMEngine @Inject constructor(
    private val settings: AiSettings,
    private val embeddingEngine: EmbeddingEngine,
) : LLMEngine {
    private val resilient = ResilientChat()

    override val isReady: Boolean get() = settings.cloudActive

    override suspend fun load(modelPath: String) = Unit

    override fun chat(messages: List<ChatMessage>, maxTokens: Int, thinking: Boolean): Flow<ChatEvent> = flow {
        val provider = settings.activeProvider()
        val config = provider?.let { settings.configFor(it.id) }
        if (provider == null || config == null) {
            throw ProviderException(ProviderError(ErrorKind.INVALID_KEY, detail = "Aucune clé API enregistrée."))
        }
        val request = ChatRequest(
            model = config.model,
            messages = messages,
            maxTokens = maxTokens,
            thinking = thinking && provider.capabilities.thinkingToggle,
        )
        resilient.stream(provider, config, request, allowFallback = settings.autoFallback).collect { ev ->
            if (ev is ChatEvent.Finished) ev.model?.let { settings.lastModelUsed = it }
            emit(ev)
        }
    }

    // Raw-prompt entry points are only used by the local backend; kept for interface compatibility.
    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = flow {
        chat(listOf(ChatMessage(ChatRole.USER, prompt)), maxTokens).collect { if (it is ChatEvent.Delta) emit(LLMToken(it.text, false)) }
        emit(LLMToken("", true))
    }

    override suspend fun generateFull(prompt: String, maxTokens: Int): String =
        generate(prompt, maxTokens).toList().joinToString("") { it.text }

    override suspend fun embed(text: String): FloatArray = embeddingEngine.embed(text)

    override fun release() = Unit
}
