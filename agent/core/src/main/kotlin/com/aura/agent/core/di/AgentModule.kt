package com.aura.agent.core.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// All bindings in this module are @Singleton via @Inject constructors.
// This placeholder keeps the module convention explicit for future additions.
@Module
@InstallIn(SingletonComponent::class)
object AgentModule
