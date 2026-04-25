package com.aura.agent.actions.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aura.agent.actions.ToolHandler
import com.aura.core.domain.model.ToolType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationSendHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolHandler {
    override val toolType = ToolType.NOTIFICATION_SEND

    companion object {
        const val CHANNEL_ID = "aura_agent"
        const val CHANNEL_NAME = "Aura Agent"
    }

    override suspend fun execute(params: Map<String, String>): String {
        val title = params["title"] ?: "Aura"
        val body = params["body"] ?: return "Missing 'body' parameter."
        val notifId = params["id"]?.toIntOrNull() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        nm.notify(notifId, notification)
        return "Notification sent: '$title' - '$body'"
    }
}
