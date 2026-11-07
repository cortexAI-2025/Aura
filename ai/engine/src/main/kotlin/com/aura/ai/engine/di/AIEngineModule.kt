package com.aura.ai.engine.di

import android.content.Context
import com.aura.ai.engine.LLMEngine
import com.aura.ai.engine.RoutedLLMEngine
import com.aura.ai.engine.SecureKeyValueStore
import com.aura.ai.provider.OpenRouterProvider
import com.aura.ai.provider.ProviderRegistry
import com.aura.ai.provider.ZaiProvider
import com.aura.ai.settings.AiSettings
import com.aura.ai.settings.KeyValueStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AIEngineModule {
    @Binds @Singleton
    abstract fun bindLLMEngine(impl: RoutedLLMEngine): LLMEngine

    companion object {
        /** Only providers that are really implemented are registered; the roadmap lives in [ProviderRegistry.PLANNED]. */
        @Provides @Singleton
        fun providerRegistry(): ProviderRegistry = ProviderRegistry(listOf(ZaiProvider(), OpenRouterProvider()))

        @Provides @Singleton
        fun secureStore(@ApplicationContext context: Context): KeyValueStore = SecureKeyValueStore.create(context)

        @Provides @Singleton
        fun aiSettings(store: KeyValueStore, registry: ProviderRegistry): AiSettings = AiSettings(store, registry)
    }
}
