package com.aura.ai.engine.di

import com.aura.ai.engine.LLMEngine
import com.aura.ai.engine.MediaPipeLLM
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AIEngineModule {
    @Binds @Singleton
    abstract fun bindLLMEngine(impl: MediaPipeLLM): LLMEngine
}
