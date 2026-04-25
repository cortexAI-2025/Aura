package com.aura.core.domain.repository

import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.MemoryType
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    suspend fun store(memory: Memory)
    suspend fun retrieve(id: String): Memory?
    suspend fun search(queryEmbedding: FloatArray, topK: Int = 5, type: MemoryType? = null): List<MemoryResult>
    suspend fun delete(id: String)
    fun observeAll(type: MemoryType? = null): Flow<List<Memory>>
    suspend fun prune(maxCount: Int = 10_000)
}
