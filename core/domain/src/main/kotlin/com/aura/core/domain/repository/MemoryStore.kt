package com.aura.core.domain.repository

import com.aura.core.domain.model.Memory
import kotlinx.coroutines.flow.Flow

/** Persistence port used by the memory service (Room in the app, an in-memory fake in unit tests). */
interface MemoryStore {
    suspend fun all(): List<Memory>
    fun observeAll(): Flow<List<Memory>>
    suspend fun get(id: String): Memory?
    suspend fun upsert(memory: Memory)
    suspend fun delete(id: String)
    suspend fun deleteAll()
    suspend fun touch(id: String, nowMs: Long)
}
