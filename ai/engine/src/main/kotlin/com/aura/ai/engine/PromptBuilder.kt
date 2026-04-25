package com.aura.ai.engine

import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.MemoryResult
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.model.ToolType
import com.aura.core.domain.model.UserRules
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Builds structured prompts for the Gemma-2B-IT instruction-tuned model.
 * Uses the <start_of_turn>/<end_of_turn> template that Gemma-IT expects.
 *
 * Architecture decision — why prompts, not fine-tuning for tool use:
 *   Fine-tuning is done offline (LoRA on the negotiation/planning dataset).
 *   The prompt template teaches the base Gemma-IT model to output structured
 *   JSON actions, following the ReAct pattern (Reason + Act).
 */
class PromptBuilder @Inject constructor() {

    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")

    fun buildAgentPrompt(
        userMessage: String,
        conversationHistory: List<Message>,
        relevantMemories: List<MemoryResult>,
        activeGoals: List<Goal>,
        userRules: UserRules,
        availableTools: List<ToolType> = ToolType.entries,
    ): String = buildString {
        // System context — injected as the first user turn in Gemma-IT format
        append("<start_of_turn>user\n")
        append(buildSystemBlock(activeGoals, userRules, availableTools))
        append("\n\n")

        // Retrieved memories (RAG context)
        if (relevantMemories.isNotEmpty()) {
            append("## Relevant context from memory\n")
            relevantMemories.forEachIndexed { i, mr ->
                append("${i + 1}. [score=${String.format("%.2f", mr.score)}] ${mr.memory.content}\n")
            }
            append("\n")
        }

        // Conversation history (keep last N turns to fit context window)
        if (conversationHistory.isNotEmpty()) {
            append("## Previous conversation\n")
            conversationHistory.takeLast(6).forEach { msg ->
                val role = if (msg.role == MessageRole.USER) "User" else "Aura"
                append("$role: ${msg.content}\n")
            }
            append("\n")
        }

        append("## Current user request\n")
        append(userMessage)
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    private fun buildSystemBlock(
        activeGoals: List<Goal>,
        userRules: UserRules,
        availableTools: List<ToolType>,
    ): String = buildString {
        append("## System\n")
        append("You are Aura, a private, proactive personal AI agent running entirely on-device.\n")
        append("Current time: ${dateFormatter.format(ZonedDateTime.now())}\n\n")

        if (activeGoals.isNotEmpty()) {
            append("## User's active goals\n")
            activeGoals.forEach { append("- [${it.category.name}] ${it.title}: ${it.description}\n") }
            append("\n")
        }

        append("## Behaviour rules\n")
        userRules.budget?.let {
            append("- Budget cap: ${it.maxAmountCents / 100.0} ${it.currency}. Never spend more.\n")
            append("- Confirm purchases above: ${it.requireConfirmationAboveCents / 100.0} ${it.currency}\n")
        }
        if (userRules.requireConfirmationForSend) {
            append("- Always confirm before sending messages or making calls.\n")
        }
        append("- Autonomy level: ${userRules.autonomyLevel.name}\n\n")

        append("## Available tools\n")
        append(availableTools.joinToString(", ") { it.name })
        append("\n\n")

        append("## Output format (STRICT)\n")
        append("If you need to take an action, respond ONLY with a JSON object:\n")
        append("""{"thought":"<your reasoning>","action":{"tool":"<TOOL_TYPE>","params":{"key":"value",...}},"requires_confirmation":<true|false>}""")
        append("\n")
        append("If you have a final answer for the user, respond ONLY with:\n")
        append("""{"thought":"<your reasoning>","final_answer":"<message to user>"}""")
        append("\n")
        append("Never mix JSON and plain text. Never fabricate tool outputs.\n")
    }

    /** Wrap a tool result back into the prompt for the next reasoning step. */
    fun buildObservationPrompt(previousPrompt: String, toolName: String, result: String): String =
        buildString {
            append(previousPrompt)
            append("\n<end_of_turn>\n")
            append("<start_of_turn>user\n")
            append("Tool result from $toolName:\n$result")
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
}
