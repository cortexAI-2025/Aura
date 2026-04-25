package com.aura.agent.core

import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.AgentStep
import com.aura.core.domain.model.ToolType
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Parses the structured JSON output produced by the LLM.
 *
 * Expected formats:
 *   Action:       {"thought":"...","action":{"tool":"TOOL_TYPE","params":{...}},"requires_confirmation":false}
 *   Final answer: {"thought":"...","final_answer":"..."}
 *
 * Fault-tolerance: if the LLM produces malformed JSON (common with 2B models),
 * [parse] falls back to treating the entire response as a final answer, and
 * attempts regex extraction as a second heuristic.
 */
class LLMResponseParser @Inject constructor() {

    fun parse(raw: String): AgentStep {
        val json = extractJson(raw)
        if (json != null) {
            return try {
                parseJson(json)
            } catch (e: Exception) {
                Timber.w(e, "JSON parse failed, treating as final answer")
                AgentStep(thought = "", action = null, observation = null, isFinal = true, finalAnswer = raw)
            }
        }
        // No JSON found — treat as plain-text final answer
        return AgentStep(thought = "", action = null, observation = null, isFinal = true, finalAnswer = raw)
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun parseJson(json: String): AgentStep {
        val obj = JSONObject(json)
        val thought = obj.optString("thought", "")

        if (obj.has("final_answer")) {
            return AgentStep(
                thought = thought,
                action = null,
                observation = null,
                isFinal = true,
                finalAnswer = obj.getString("final_answer"),
            )
        }

        val actionObj = obj.optJSONObject("action")
            ?: return AgentStep(thought = thought, action = null, observation = null, isFinal = true, finalAnswer = thought)

        val toolName = actionObj.optString("tool", "UNKNOWN")
        val tool = runCatching { ToolType.valueOf(toolName) }.getOrDefault(ToolType.UNKNOWN)
        val paramsObj = actionObj.optJSONObject("params") ?: JSONObject()
        val params = buildMap { paramsObj.keys().forEach { key -> put(key, paramsObj.optString(key)) } }
        val requiresConfirmation = obj.optBoolean("requires_confirmation", false)

        val action = AgentAction(
            id = UUID.randomUUID().toString(),
            tool = tool,
            params = params,
            reasoning = thought,
            requiresConfirmation = requiresConfirmation,
        )
        return AgentStep(thought = thought, action = action, observation = null, isFinal = false)
    }
}
