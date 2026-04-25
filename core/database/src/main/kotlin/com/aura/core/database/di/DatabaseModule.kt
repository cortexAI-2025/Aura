package com.aura.core.database.di

import android.content.Context
import com.aura.core.database.AuraDatabase
import com.aura.core.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AuraDatabase =
        AuraDatabase.create(context)

    @Provides fun provideMessageDao(db: AuraDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: AuraDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideGoalDao(db: AuraDatabase): GoalDao = db.goalDao()
    @Provides fun provideActionLogDao(db: AuraDatabase): ActionLogDao = db.actionLogDao()
    @Provides fun provideKnowledgeDao(db: AuraDatabase): KnowledgeDao = db.knowledgeDao()
}
