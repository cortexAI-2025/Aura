package com.aura.agent.memory

import com.aura.core.common.cosineSimilarity
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemoryType
import com.aura.core.database.dao.MemoryDao
import com.aura.core.database.entity.MemoryEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process approximate nearest-neighbour vector store backed by Room.
 *
 * Architecture rationale:
 *   SQLite does not support vector indices natively. For MVP (<10k memories)
 *   a linear scan over all stored embeddings (loaded into RAM) is fast enough
 *   (<5ms for 5000 vectors at d=384 on a mid-range CPU).
 *   For production scale, swap in FAISS via JNI or SQLite-VSS extension.
 *
 * Embeddings are stored as raw IEEE-754 ByteArray blobs in Room and decoded
 * on load. This avoids CSV serialisation overhead.
 */
@Singleton
class VectorStore @Inject constructor(
    private val memoryDao: MemoryDao,
) {
    private val mutex = Mutex()

    /** Add or update a memory. The embedding must already be computed. */
    suspend fun upsert(memory: Memory) = mutex.withLock {
        memoryDao.insert(memory.toEntity())
    }

    suspend fun delete(id: String) = mutex.withLock { memoryDao.delete(id) }

    suspend fun get(id: String): Memory? = memoryDao.getById(id)?.toDomain()

    /**
     * Retrieve top-[k] memories most similar to [queryEmbedding].
     * Applies an optional [typeFilter] and an optional minimum [threshold] score.
     */
    suspend fun search(
        queryEmbedding: FloatArray,
        k: Int = 5,
        typeFilter: MemoryType? = null,
        threshold: Float = 0.35f,
    ): List<MemoryResult> = mutex.withLock {
        val candidates = memoryDao.getAll(type = typeFilter?.name, limit = 8000)
        Timber.d("VectorStore: scanning ${candidates.size} memories")

        candidates
            .map { entity ->
                val emb = entity.embeddingBlob.toFloatArray()
                MemoryResult(entity.toDomain(), queryEmbedding.cosineSimilarity(emb))
            }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(k)
            .also { results ->
                // Update access stats for retrieved memories
                val nowMs = Instant.now().toEpochMilli()
                results.forEach { memoryDao.touchMemory(it.memory.id, nowMs) }
            }
    }

    suspend fun count(): Int = memoryDao.count()

    suspend fun prune(maxCount: Int) {
        val current = memoryDao.count()
        if (current > maxCount) {
            memoryDao.pruneOldest(current - maxCount)
            Timber.i("VectorStore pruned ${current - maxCount} memories")
        }
    }
}

// ─── ByteArray ↔ FloatArray conversions ──────────────────────────────────────

private fun FloatArray.toByteArray(): ByteArray {
    val buf = java.nio.ByteBuffer.allocate(size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

private fun ByteArray.toFloatArray(): FloatArray {
    val buf = java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.float }
}

// ─── Domain ↔ Entity mappers ──────────────────────────────────────────────────

private fun Memory.toEntity() = MemoryEntity(
    id = id, content = content, embeddingBlob = embedding.toByteArray(),
    type = type.name, importance = importance,
    createdAtMs = createdAt.toEpochMilli(), accessedAtMs = accessedAt.toEpochMilli(),
    accessCount = accessCount, tags = tags.joinToString(","), linkedGoalId = linkedGoalId,
)

private fun MemoryEntity.toDomain() = Memory(
    id = id, content = content, embedding = embeddingBlob.toFloatArray(),
    type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.EPISODIC),
    importance = importance, createdAt = Instant.ofEpochMilli(createdAtMs),
    accessedAt = Instant.ofEpochMilli(accessedAtMs), accessCount = accessCount,
    tags = if (tags.isBlank()) emptyList() else tags.split(","),
    linkedGoalId = linkedGoalId,
)
