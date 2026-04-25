package com.aura.agent.actions.tools

import com.aura.agent.actions.AuraAccessibilityService
import com.aura.agent.actions.ToolHandler
import com.aura.core.domain.model.ToolType
import javax.inject.Inject

class ScreenReadHandler @Inject constructor() : ToolHandler {
    override val toolType = ToolType.SCREEN_READ

    override suspend fun execute(params: Map<String, String>): String {
        if (!AuraAccessibilityService.isRunning) {
            return "Accessibility service not enabled. Ask user to enable Aura in Settings > Accessibility."
        }
        val snapshot = AuraAccessibilityService.screenContent.value
            ?: return "Screen is blank or inaccessible."
        return snapshot.toReadableString()
    }
}
