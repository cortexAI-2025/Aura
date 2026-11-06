package com.aura.ai.engine

import com.aura.ai.provider.ChatEvent
import com.aura.ai.provider.ChatMessage
import com.aura.ai.settings.AiSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Routes each call to the selected cloud provider (key present) or to the on-device model. */
@Singleton
class RoutedLLMEngine @Inject constructor(
    private val settings: AiSettings,
    private val local: MediaPipeLLM,
    private val cloud: CloudLLMEngine,
) : LLMEngine {

    private val active: LLMEngine get() = if (settings.cloudActive) cloud else local

    override val isReady: Boolean get() = active.isReady
    override suspend fun load(modelPath: String) = local.load(modelPath)
    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = active.generate(prompt, maxTokens)
    override suspend fun generateFull(prompt: String, maxTokens: Int): String = active.generateFull(prompt, maxTokens)
    override fun chat(messages: List<ChatMessage>, maxTokens: Int, thinking: Boolean): Flow<ChatEvent> =
        active.chat(messages, maxTokens, thinking)
    override suspend fun embed(text: String): FloatArray = active.embed(text)
    override fun release() { local.release(); cloud.release() }
}
