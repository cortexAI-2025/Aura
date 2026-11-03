package com.aura.agent.actions.di

import com.aura.agent.actions.ToolHandler
import com.aura.agent.actions.tools.CalendarReadHandler
import com.aura.agent.actions.tools.CalendarWriteHandler
import com.aura.agent.actions.tools.MessageSendHandler
import com.aura.agent.actions.tools.NotificationSendHandler
import com.aura.agent.actions.tools.ScreenReadHandler
import com.aura.core.domain.model.ToolType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Map<ToolType, ToolHandler> consumed by ActionExecutor.
 *
 * An explicit @Provides is used instead of @Binds @IntoMap @EnumKey: the enum
 * lives in another module and KSP cannot resolve it as an annotation argument.
 * To add a new tool: implement ToolHandler and register it here.
 */
@Module
@InstallIn(SingletonComponent::class)
object ActionModule {

    @Provides
    @Singleton
    fun provideToolHandlers(
        calendarRead: CalendarReadHandler,
        calendarWrite: CalendarWriteHandler,
        notificationSend: NotificationSendHandler,
        screenRead: ScreenReadHandler,
        messageSend: MessageSendHandler,
    ): Map<ToolType, @JvmSuppressWildcards ToolHandler> = mapOf(
        ToolType.CALENDAR_READ to calendarRead,
        ToolType.CALENDAR_WRITE to calendarWrite,
        ToolType.NOTIFICATION_SEND to notificationSend,
        ToolType.SCREEN_READ to screenRead,
        ToolType.MESSAGE_SEND to messageSend,
    )
}
