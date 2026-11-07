package com.aura.ai.brain

import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRole.*
import org.junit.Assert.*
import org.junit.Test

class GemmaPromptTest {
    @Test fun `system text opens the first user turn and the prompt ends on the model turn`() {
        val p = GemmaPrompt.format(listOf(ChatMessage(SYSTEM, "Tu es Aura."), ChatMessage(USER, "Salut")))
        assertEquals("<start_of_turn>user\nTu es Aura.\n\nSalut<end_of_turn>\n<start_of_turn>model\n", p)
    }

    @Test fun `history keeps roles and consecutive turns are merged`() {
        val p = GemmaPrompt.format(listOf(
            ChatMessage(USER, "a"), ChatMessage(USER, "b"), ChatMessage(ASSISTANT, "c"), ChatMessage(USER, "d"),
        ))
        assertEquals("<start_of_turn>user\na\n\nb<end_of_turn>\n<start_of_turn>model\nc<end_of_turn>\n<start_of_turn>user\nd<end_of_turn>\n<start_of_turn>model\n", p)
    }

    @Test fun `blank messages are dropped and a lone system prompt still yields a turn`() {
        val p = GemmaPrompt.format(listOf(ChatMessage(SYSTEM, "S"), ChatMessage(USER, "  ")))
        assertEquals("<start_of_turn>user\nS<end_of_turn>\n<start_of_turn>model\n", p)
    }
}
