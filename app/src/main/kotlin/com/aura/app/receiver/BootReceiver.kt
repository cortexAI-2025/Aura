package com.aura.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aura.agent.core.AuraAgentService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, AuraAgentService::class.java))
        }
    }
}
