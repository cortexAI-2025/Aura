package com.aura.agent.memory.logic

import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemorySource
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.repository.MemoryStore
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

sealed interface RememberResult {
    /** Saved. [Memory.needsReview] tells whether a similar memory exists that the user should check. */
    data class Saved(val memory: Memory) : RememberResult
    data class Duplicate(val existing: Memory) : RememberResult
    data class Rejected(val reason: RejectReason) : RememberResult
}

/**
 * The three memory layers on top of a [MemoryStore]:
 *  - persistent: explicit memories the user can list, edit, delete, export and wipe;
 *  - semantic: hybrid recall (real embeddings when available, lexical otherwise);
 *  - hygiene: secrets refused, duplicates merged, possible contradictions flagged for review.
 */
class MemoryService(
    private val store: MemoryStore,
    private val embedder: Embedder,
    private val clock: () -> Instant = { Instant.now() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeAll(): Flow<List<Memory>> = store.observeAll()

    suspend fun list(): List<Memory> = store.all().sortedByDescending { it.updatedAt }

    suspend fun count(): Int = store.all().size

    suspend fun remember(
        text: String,
        source: MemorySource = MemorySource.USER,
        type: MemoryType = MemoryType.SEMANTIC,
        importance: Float = 0.6f,
        linkedGoalId: String? = null,
    ): RememberResult {
        val clean = squash(text)
        MemoryPolicy.precheck(clean)?.let { return RememberResult.Rejected(it) }
        val emb = embedder.embed(clean)
        val (similar, score) = mostSimilar(clean, emb, excludeId = null)
        return when (val d = MemoryPolicy.decide(clean, similar, score)) {
            is MemoryDecision.Duplicate -> {
                // Nothing new to learn: only record that it was said again.
                store.upsert(d.existing.copy(updatedAt = clock()))
                RememberResult.Duplicate(d.existing)
            }
            is MemoryDecision.Accept -> {
                val now = clock()
                val memory = Memory(
                    id = newId(), content = clean, embedding = emb.vector, type = type,
                    importance = importance, createdAt = now, accessedAt = now, updatedAt = now,
                    linkedGoalId = linkedGoalId, source = source,
                    needsReview = d.relatedTo != null, relatedId = d.relatedTo?.id,
                    embeddingKind = emb.kind,
                )
                store.upsert(memory)
                RememberResult.Saved(memory)
            }
        }
    }

    /** User correction of a memory. Same hygiene as [remember], excluding the memory itself. */
    suspend fun update(id: String, newText: String): RememberResult {
        val current = store.get(id) ?: return RememberResult.Rejected(RejectReason.EMPTY)
        val clean = squash(newText)
        MemoryPolicy.precheck(clean)?.let { return RememberResult.Rejected(it) }
        val emb = embedder.embed(clean)
        val (similar, score) = mostSimilar(clean, emb, excludeId = id)
        val related = (MemoryPolicy.decide(clean, similar, score) as? MemoryDecision.Accept)?.relatedTo
        val updated = current.copy(
            content = clean, embedding = emb.vector, embeddingKind = emb.kind, updatedAt = clock(),
            needsReview = related != null, relatedId = related?.id,
        )
        store.upsert(updated)
        return RememberResult.Saved(updated)
    }

    suspend fun delete(id: String) = store.delete(id)

    suspend fun deleteAll() = store.deleteAll()

    /** "Keep both": the user checked, the warning goes away. */
    suspend fun acceptReview(id: String) {
        val m = store.get(id) ?: return
        store.upsert(m.copy(needsReview = false, relatedId = null))
    }

    /** Relevant memories for a question, best first. Touches the hits (access statistics). */
    suspend fun recall(query: String, topK: Int = 5, minScore: Double = MIN_RECALL_SCORE): List<MemoryResult> {
        if (query.isBlank()) return emptyList()
        val emb = embedder.embed(query)
        val hits = store.all()
            .map { it to HybridRanker.relevance(query, emb, it) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .take(topK)
        val now = clock().toEpochMilli()
        hits.forEach { store.touch(it.first.id, now) }
        return hits.map { MemoryResult(it.first, it.second.toFloat()) }
    }

    /** Vector-only search kept for the legacy [com.aura.core.domain.repository.MemoryRepository.search] entry point. */
    suspend fun searchByVector(vector: FloatArray, kind: String, topK: Int): List<MemoryResult> =
        store.all()
            .filter { it.embeddingKind == kind && kind != STUB_KIND }
            .map { it to HybridRanker.cosine(vector, it.embedding) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { MemoryResult(it.first, it.second.toFloat()) }

    /** Text filter for the memory screen (no model needed). */
    suspend fun search(query: String): List<Memory> {
        if (query.isBlank()) return list()
        val q = TextSimilarity.normalize(query).trim()
        return list().filter { TextSimilarity.normalize(it.content).contains(q) || TextSimilarity.relevance(query, it.content) >= 0.5 }
    }

    /** Deletes the single memory that best matches [query] (chat command "oublie que…"). */
    suspend fun forget(query: String): Memory? {
        val best = recall(query, topK = 1, minScore = FORGET_MIN_SCORE).firstOrNull() ?: return null
        store.delete(best.memory.id)
        return best.memory
    }

    /** Portable export: readable JSON, no embeddings (recomputed on demand), never any secret. */
    suspend fun exportJson(): String {
        val all = list()
        val arr = JSONArray()
        all.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id).put("content", m.content).put("type", m.type.name).put("source", m.source.name)
                    .put("importance", m.importance.toDouble()).put("tags", JSONArray(m.tags))
                    .put("createdAt", m.createdAt.toString()).put("updatedAt", m.updatedAt.toString())
                    .put("needsReview", m.needsReview).put("relatedId", m.relatedId ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("app", "Aura").put("format", "aura-memory").put("version", 1)
            .put("exportedAt", clock().toString()).put("count", all.size).put("memories", arr)
            .toString(2)
    }

    private suspend fun mostSimilar(text: String, emb: Embedding, excludeId: String?): Pair<Memory?, Double> {
        var best: Memory? = null
        var bestScore = 0.0
        for (m in store.all()) {
            if (m.id == excludeId) continue
            val s = HybridRanker.similarity(text, emb, m)
            if (s > bestScore) { best = m; bestScore = s }
        }
        return best to bestScore
    }

    private fun squash(text: String) = text.trim().replace(Regex("\\s+"), " ")

    companion object {
        const val MIN_RECALL_SCORE = 0.3
        const val FORGET_MIN_SCORE = 0.6
    }
}
