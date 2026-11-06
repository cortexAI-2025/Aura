package com.aura.core.data.repository

import com.aura.core.database.dao.KnowledgeDao
import com.aura.core.database.dao.MemoryDao
import com.aura.core.database.entity.MemoryEntity
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemorySource
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.repository.MemoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMemoryStore @Inject constructor(
    private val dao: MemoryDao,
    private val knowledge: KnowledgeDao,
) : MemoryStore {
    override suspend fun all(): List<Memory> = dao.listAll().map { it.toDomain() }

    override fun observeAll(): Flow<List<Memory>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() }.sortedByDescending { it.updatedAt } }

    override suspend fun get(id: String): Memory? = dao.getById(id)?.toDomain()
    override suspend fun upsert(memory: Memory) = dao.insert(memory.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)

    /** Wipes memories AND the knowledge graph derived from them (it holds personal entities too). */
    override suspend fun deleteAll() {
        dao.deleteAll()
        knowledge.deleteAllEdges()
        knowledge.deleteAllNodes()
    }

    override suspend fun touch(id: String, nowMs: Long) = dao.touchMemory(id, nowMs)
}

internal fun FloatArray.toBlob(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

internal fun ByteArray.toFloats(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.float }
}

private fun MemoryEntity.toDomain() = Memory(
    id = id, content = content, embedding = embeddingBlob.toFloats(),
    type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.SEMANTIC),
    importance = importance, createdAt = Instant.ofEpochMilli(createdAtMs),
    accessedAt = Instant.ofEpochMilli(accessedAtMs), accessCount = accessCount,
    tags = if (tags.isBlank()) emptyList() else tags.split(","),
    linkedGoalId = linkedGoalId,
    source = runCatching { MemorySource.valueOf(source) }.getOrDefault(MemorySource.USER),
    needsReview = needsReview, relatedId = relatedId,
    updatedAt = Instant.ofEpochMilli(updatedAtMs), embeddingKind = embeddingKind,
)

private fun Memory.toEntity() = MemoryEntity(
    id = id, content = content, embeddingBlob = embedding.toBlob(), type = type.name,
    importance = importance, createdAtMs = createdAt.toEpochMilli(), accessedAtMs = accessedAt.toEpochMilli(),
    accessCount = accessCount, tags = tags.joinToString(","), linkedGoalId = linkedGoalId,
    source = source.name, needsReview = needsReview, relatedId = relatedId,
    updatedAtMs = updatedAt.toEpochMilli(), embeddingKind = embeddingKind,
)
