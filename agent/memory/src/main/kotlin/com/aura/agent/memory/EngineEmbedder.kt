package com.aura.agent.memory

import com.aura.agent.memory.logic.Embedder
import com.aura.agent.memory.logic.Embedding
import com.aura.ai.engine.EmbeddingEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Bridges the on-device [EmbeddingEngine] to the pure memory logic, tagging each vector with its space. */
@Singleton
class EngineEmbedder @Inject constructor(private val engine: EmbeddingEngine) : Embedder {
    override suspend fun embed(text: String): Embedding {
        val (vector, kind) = engine.embedTagged(text)
        return Embedding(vector, kind)
    }
}
