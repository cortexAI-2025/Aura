package com.aura.agent.actions

import com.aura.core.domain.model.ActionResult
import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.ToolType
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes an [AgentAction] to the appropriate [ToolHandler] and returns the result.
 *
 * [ActionGuard] must be consulted BEFORE calling [execute] — the executor assumes
 * the action has already been cleared. This separation keeps the executor simple
 * and makes guard logic independently testable.
 */
@Singleton
class ActionExecutor @Inject constructor(
    private val handlers: Map<ToolType, @JvmSuppressWildcards ToolHandler>,
) {
    suspend fun execute(action: AgentAction): ActionResult {
        Timber.i("Executing action: ${action.tool} params=${action.params}")
        val handler = handlers[action.tool]
        return if (handler == null) {
            Timber.w("No handler for tool ${action.tool}")
            ActionResult(action.id, action.tool, false, "Tool '${action.tool}' not available on this device.")
        } else {
            try {
                val output = handler.execute(action.params)
                ActionResult(action.id, action.tool, true, output)
            } catch (e: Exception) {
                Timber.e(e, "Action ${action.tool} failed")
                ActionResult(action.id, action.tool, false, "Error: ${e.message}")
            }
        }
    }
}

/** Contract every tool must implement. */
interface ToolHandler {
    val toolType: ToolType
    suspend fun execute(params: Map<String, String>): String
}
