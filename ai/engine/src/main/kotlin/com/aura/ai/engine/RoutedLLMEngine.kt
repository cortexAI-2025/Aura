package com.aura.ai.engine

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Routes each call to OpenRouter (when opted in) or the on-device MediaPipe model. */
@Singleton
class RoutedLLMEngine @Inject constructor(
    private val settings: LLMSettings,
    private val local: MediaPipeLLM,
    private val cloud: OpenRouterLLM,
) : LLMEngine {

    private val active: LLMEngine get() = if (settings.cloudActive) cloud else local

    override val isReady: Boolean get() = active.isReady
    override suspend fun load(modelPath: String) = local.load(modelPath)
    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = active.generate(prompt, maxTokens)
    override suspend fun generateFull(prompt: String, maxTokens: Int): String = active.generateFull(prompt, maxTokens)
    override suspend fun embed(text: String): FloatArray = active.embed(text)
    override fun release() { local.release(); cloud.release() }
}
