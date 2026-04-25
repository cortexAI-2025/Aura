package com.aura.core.common

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class Dispatcher(val auraDispatcher: AuraDispatchers)

enum class AuraDispatchers { Default, IO, Main }
