package com.aura.agent.memory.logic

import com.aura.core.domain.model.Memory
import com.aura.core.domain.repository.MemoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

class FakeMemoryStore : MemoryStore {
    private val items = LinkedHashMap<String, Memory>()
    private val flow = MutableStateFlow<List<Memory>>(emptyList())
    private fun publish() { flow.value = items.values.toList() }

    override suspend fun all() = items.values.toList()
    override fun observeAll(): Flow<List<Memory>> = flow
    override suspend fun get(id: String) = items[id]
    override suspend fun upsert(memory: Memory) { items[memory.id] = memory; publish() }
    override suspend fun delete(id: String) { items.remove(id); publish() }
    override suspend fun deleteAll() { items.clear(); publish() }
    override suspend fun touch(id: String, nowMs: Long) {
        items[id]?.let { items[id] = it.copy(accessedAt = Instant.ofEpochMilli(nowMs), accessCount = it.accessCount + 1) }
        publish()
    }
}

/** No model installed: hashing stub, no meaning. */
class StubEmbedder : Embedder {
    override suspend fun embed(text: String) = Embedding(FloatArray(8) { (text.length % 7).toFloat() }, STUB_KIND)
}

/** A pretend semantic model: words of the same concept map to the same axis. */
class ConceptEmbedder(private val concepts: Map<String, Int>, private val kind: String = "fake-semantic") : Embedder {
    override suspend fun embed(text: String): Embedding {
        val v = FloatArray(4)
        TextSimilarity.tokens(text).forEach { t -> concepts[t]?.let { v[it] += 1f } }
        return Embedding(v, kind)
    }
}
