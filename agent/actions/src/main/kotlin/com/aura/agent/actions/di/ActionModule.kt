package com.aura.agent.actions.di

import com.aura.agent.actions.ToolHandler
import com.aura.agent.actions.tools.*
import com.aura.core.domain.model.ToolType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.EnumKey

/**
 * Hilt multibinding: each ToolHandler implementation is bound into a
 * Map<ToolType, ToolHandler> that ActionExecutor receives via injection.
 * To add a new tool: implement ToolHandler, add a @Binds @IntoMap entry here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ActionModule {

    @Binds @IntoMap @EnumKey(ToolType.CALENDAR_READ)
    abstract fun bindCalendarRead(impl: CalendarReadHandler): ToolHandler

    @Binds @IntoMap @EnumKey(ToolType.CALENDAR_WRITE)
    abstract fun bindCalendarWrite(impl: CalendarWriteHandler): ToolHandler

    @Binds @IntoMap @EnumKey(ToolType.NOTIFICATION_SEND)
    abstract fun bindNotificationSend(impl: NotificationSendHandler): ToolHandler

    @Binds @IntoMap @EnumKey(ToolType.SCREEN_READ)
    abstract fun bindScreenRead(impl: ScreenReadHandler): ToolHandler

    @Binds @IntoMap @EnumKey(ToolType.MESSAGE_SEND)
    abstract fun bindMessageSend(impl: MessageSendHandler): ToolHandler
}
