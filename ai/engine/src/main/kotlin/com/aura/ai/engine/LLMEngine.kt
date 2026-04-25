package com.aura.ai.engine

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the on-device language model.
 * Concrete implementations: [MediaPipeLLM] (Gemma via MediaPipe Tasks GenAI).
 */
interface LLMEngine {
    val isReady: Boolean

    /**
     * Load the model from [modelPath] (absolute path on device, e.g. /data/local/tmp/gemma-2b-it-gpu-int4.bin).
     * Must be called before [generate].
     */
    suspend fun load(modelPath: String)

    /**
     * Stream tokens for a given [prompt].
     * Emits partial strings as they are generated; final emission is the full response.
     */
    fun generate(prompt: String, maxTokens: Int = 1024): Flow<LLMToken>

    /** Non-streaming convenience wrapper — collects full response. */
    suspend fun generateFull(prompt: String, maxTokens: Int = 1024): String

    /**
     * Generate a fixed-dimension embedding for [text].
     * Used for semantic memory indexing and RAG retrieval.
     * NOTE: MediaPipe Tasks GenAI does not expose embeddings directly;
     * this delegates to a lightweight embedding model (MiniLM or BGE-small quantised).
     */
    suspend fun embed(text: String): FloatArray

    fun release()
}

data class LLMToken(val text: String, val isDone: Boolean)
