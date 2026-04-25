package com.aura.core.data.di

import com.aura.core.data.repository.ConversationRepositoryImpl
import com.aura.core.data.repository.GoalRepositoryImpl
import com.aura.core.domain.repository.ConversationRepository
import com.aura.core.domain.repository.GoalRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton
    abstract fun bindConversationRepository(impl: ConversationRepositoryImpl): ConversationRepository

    @Binds @Singleton
    abstract fun bindGoalRepository(impl: GoalRepositoryImpl): GoalRepository
}
