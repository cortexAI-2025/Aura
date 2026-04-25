package com.aura.agent.memory

import com.aura.ai.engine.EmbeddingEngine
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level memory façade used by the agent.
 *
 * Implements the three-tier memory model:
 *   1. Working memory  — in-RAM list, never persisted, cleared each session.
 *   2. Episodic memory — specific events/conversations, stored in VectorStore.
 *   3. Semantic memory — distilled facts/preferences, stored in VectorStore.
 *
 * Importance scoring follows a simple heuristic:
 *   base = 0.5, +0.2 if explicitly confirmed, +0.3 if linked to an active goal.
 *   Decays by 0.01 per access-day (Ebbinghaus-inspired forgetting curve).
 */
@Singleton
class MemoryManager @Inject constructor(
    private val vectorStore: VectorStore,
    private val embeddingEngine: EmbeddingEngine,
    private val knowledgeGraph: KnowledgeGraph,
) : MemoryRepository {

    private val workingMemory = ArrayDeque<Memory>(128)

    // ─── Working memory ───────────────────────────────────────────────────────

    fun addToWorkingMemory(text: String, linkedGoalId: String? = null) {
        if (workingMemory.size >= 128) workingMemory.removeFirst()
        workingMemory.addLast(
            Memory(
                id = UUID.randomUUID().toString(),
                content = text,
                embedding = FloatArray(EmbeddingEngine.EMBEDDING_DIM),
                type = MemoryType.WORKING,
                linkedGoalId = linkedGoalId,
            )
        )
    }

    fun getWorkingMemory(): List<Memory> = workingMemory.toList()
    fun clearWorkingMemory() = workingMemory.clear()

    // ─── Persistent memory ────────────────────────────────────────────────────

    override suspend fun store(memory: Memory) {
        val withEmbedding = if (memory.embedding.all { it == 0f }) {
            memory.copy(embedding = embeddingEngine.embed(memory.content))
        } else memory
        vectorStore.upsert(withEmbedding)
        // Optionally extract and store entities in the knowledge graph
        knowledgeGraph.extractAndStore(withEmbedding.content, withEmbedding.id)
        Timber.d("Memory stored: ${memory.id} type=${memory.type}")
    }

    /** Convenience factory — embeds text and stores as episodic memory. */
    suspend fun rememberEvent(
        text: String,
        importance: Float = 0.5f,
        linkedGoalId: String? = null,
        tags: List<String> = emptyList(),
    ): Memory {
        val memory = Memory(
            id = UUID.randomUUID().toString(),
            content = text,
            embedding = embeddingEngine.embed(text),
            type = MemoryType.EPISODIC,
            importance = importance,
            createdAt = Instant.now(),
            linkedGoalId = linkedGoalId,
            tags = tags,
        )
        vectorStore.upsert(memory)
        return memory
    }

    /** Store a distilled fact (semantic). */
    suspend fun rememberFact(
        fact: String,
        importance: Float = 0.7f,
        linkedGoalId: String? = null,
    ): Memory {
        val memory = Memory(
            id = UUID.randomUUID().toString(),
            content = fact,
            embedding = embeddingEngine.embed(fact),
            type = MemoryType.SEMANTIC,
            importance = importance,
            createdAt = Instant.now(),
            linkedGoalId = linkedGoalId,
        )
        vectorStore.upsert(memory)
        return memory
    }

    override suspend fun retrieve(id: String): Memory? = vectorStore.get(id)

    override suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        type: MemoryType?,
    ): List<MemoryResult> = vectorStore.search(queryEmbedding, topK, type)

    override suspend fun delete(id: String) = vectorStore.delete(id)

    override fun observeAll(type: MemoryType?): Flow<List<Memory>> {
        throw UnsupportedOperationException("Use VectorStore.observeAll() directly")
    }

    override suspend fun prune(maxCount: Int) = vectorStore.prune(maxCount)
}
