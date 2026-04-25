package com.aura.agent.memory.di

import com.aura.agent.memory.MemoryManager
import com.aura.core.domain.repository.MemoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {
    @Binds @Singleton
    abstract fun bindMemoryRepository(impl: MemoryManager): MemoryRepository
}
