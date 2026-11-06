package com.aura.ai.brain

import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRole

/** Renders chat messages in the `<start_of_turn>` template expected by Gemma-IT (on-device model). */
object GemmaPrompt {
    fun format(messages: List<ChatMessage>): String {
        val system = messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }.trim()
        // Gemma has no system role: the system text opens the first user turn.
        val turns = ArrayList<Pair<ChatRole, String>>()
        for (m in messages) {
            if (m.role == ChatRole.SYSTEM || m.content.isBlank()) continue
            val last = turns.lastOrNull()
            if (last != null && last.first == m.role) turns[turns.size - 1] = m.role to (last.second + "\n\n" + m.content.trim())
            else turns += m.role to m.content.trim()
        }
        if (system.isNotEmpty()) {
            if (turns.isNotEmpty() && turns.first().first == ChatRole.USER) {
                turns[0] = ChatRole.USER to (system + "\n\n" + turns.first().second)
            } else turns.add(0, ChatRole.USER to system)
        }
        return buildString {
            for ((role, content) in turns) {
                append("<start_of_turn>").append(if (role == ChatRole.ASSISTANT) "model" else "user").append('\n')
                append(content).append("<end_of_turn>\n")
            }
            if (turns.lastOrNull()?.first != ChatRole.ASSISTANT) append("<start_of_turn>model\n")
        }
    }
}
