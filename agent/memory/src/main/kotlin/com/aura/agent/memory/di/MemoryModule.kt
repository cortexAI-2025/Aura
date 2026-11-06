package com.aura.agent.memory.di

import com.aura.agent.memory.EngineEmbedder
import com.aura.agent.memory.MemoryManager
import com.aura.agent.memory.logic.Embedder
import com.aura.agent.memory.logic.MemoryService
import com.aura.core.domain.repository.MemoryRepository
import com.aura.core.domain.repository.MemoryStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {
    @Binds @Singleton
    abstract fun bindMemoryRepository(impl: MemoryManager): MemoryRepository

    @Binds @Singleton
    abstract fun bindEmbedder(impl: EngineEmbedder): Embedder

    companion object {
        @Provides @Singleton
        fun memoryService(store: MemoryStore, embedder: Embedder): MemoryService = MemoryService(store, embedder)
    }
}
