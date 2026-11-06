package com.aura.ai.brain

import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRole

/** Keeps a conversation inside a token budget: newest turns win, the system prompt and the last message always stay. */
object ContextWindow {
    /** Conservative: one token ≈ 3 characters (French and code tokenise worse than English prose). */
    fun estimateTokens(text: String): Int = (text.length + 2) / 3

    data class Fit(val messages: List<ChatMessage>, val droppedMessages: Int, val truncated: Boolean)

    private const val MIN_LAST_TOKENS = 200
    private const val MAX_SYSTEM_SHARE = 0.6

    fun fit(messages: List<ChatMessage>, budgetTokens: Int): Fit {
        if (messages.isEmpty()) return Fit(messages, 0, false)
        val system = messages.takeWhile { it.role == ChatRole.SYSTEM }.toMutableList()
        val rest = messages.drop(system.size)
        if (rest.isEmpty()) return Fit(messages, 0, false)

        var truncated = false
        // An oversized system prompt (many memories) must not starve the conversation.
        val sysCap = (budgetTokens * MAX_SYSTEM_SHARE).toInt()
        for (i in system.indices) {
            if (estimateTokens(system[i].content) > sysCap) {
                system[i] = system[i].copy(content = system[i].content.take(sysCap * 3).trimEnd() + "\n[…]")
                truncated = true
            }
        }
        val sysTokens = system.sumOf { estimateTokens(it.content) }

        var last = rest.last()
        val history = rest.dropLast(1)
        var remaining = budgetTokens - sysTokens
        val lastTokens = estimateTokens(last.content)
        if (lastTokens > remaining) {
            val allowed = remaining.coerceAtLeast(MIN_LAST_TOKENS)
            last = last.copy(content = last.content.take(allowed * 3).trimEnd() + " … [tronqué]")
            truncated = true
            remaining = 0
        } else remaining -= lastTokens

        val kept = ArrayDeque<ChatMessage>()
        for (m in history.asReversed()) {
            val t = estimateTokens(m.content)
            if (t > remaining) break
            kept.addFirst(m)
            remaining -= t
        }
        // Providers expect a conversation that starts with the user.
        while (kept.isNotEmpty() && kept.first().role == ChatRole.ASSISTANT) kept.removeFirst()

        val dropped = history.size - kept.size
        return Fit(system + kept + last, dropped, truncated)
    }
}
