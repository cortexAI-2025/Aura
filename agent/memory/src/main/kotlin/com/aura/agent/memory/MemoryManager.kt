package com.aura.agent.memory

import com.aura.agent.memory.logic.MemoryService
import com.aura.ai.engine.EmbeddingEngine
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.repository.MemoryRepository
import com.aura.core.domain.repository.MemoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MemoryRepository] façade over [MemoryService]. The service holds the logic (hygiene, hybrid recall,
 * export); this class only adapts it to the domain interface. Persistent memory is explicit: nothing is
 * written unless the user asked ("retiens que…", Memory screen) or turned automatic summaries on.
 */
@Singleton
class MemoryManager @Inject constructor(
    val service: MemoryService,
    private val store: MemoryStore,
) : MemoryRepository {

    override suspend fun store(memory: Memory) = store.upsert(memory)

    override suspend fun retrieve(id: String): Memory? = store.get(id)

    override suspend fun search(queryEmbedding: FloatArray, topK: Int, type: MemoryType?): List<MemoryResult> =
        service.searchByVector(queryEmbedding, EmbeddingEngine.KIND_MINILM, if (type == null) topK else topK * 4)
            .filter { type == null || it.memory.type == type }
            .take(topK)

    override suspend fun recall(query: String, topK: Int): List<MemoryResult> = service.recall(query, topK)

    override suspend fun delete(id: String) = service.delete(id)

    override fun observeAll(type: MemoryType?): Flow<List<Memory>> =
        service.observeAll().map { list -> if (type == null) list else list.filter { it.type == type } }

    /** Keeps the [maxCount] most valuable memories (importance, then recency). */
    override suspend fun prune(maxCount: Int) {
        val all = store.all()
        if (all.size <= maxCount) return
        all.sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.updatedAt })
            .drop(maxCount).forEach { store.delete(it.id) }
    }
}
