package com.aura.ai.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenRouterProtocolTest {

    @Test fun `gemma prompt becomes user message and drops trailing empty model turn`() {
        val prompt = "<start_of_turn>user\nHello there<end_of_turn>\n<start_of_turn>model\n"
        val turns = OpenRouterProtocol.toChatMessages(prompt)
        assertEquals(listOf(ChatTurn("user", "Hello there")), turns)
    }

    @Test fun `observation turns are kept and blank assistant turn is merged away`() {
        val prompt = "<start_of_turn>user\nQ<end_of_turn>\n<start_of_turn>model\n" +
            "\n<end_of_turn>\n<start_of_turn>user\nTool result: ok<end_of_turn>\n<start_of_turn>model\n"
        val turns = OpenRouterProtocol.toChatMessages(prompt)
        assertEquals(1, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("Q\n\nTool result: ok", turns[0].content)
    }

    @Test fun `assistant reply between turns is preserved`() {
        val prompt = "<start_of_turn>user\nQ<end_of_turn>\n<start_of_turn>model\nTHINK<end_of_turn>\n" +
            "<start_of_turn>user\nobs<end_of_turn>\n<start_of_turn>model\n"
        val roles = OpenRouterProtocol.toChatMessages(prompt).map { it.role }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test fun `plain text without markers becomes one user message`() {
        assertEquals(listOf(ChatTurn("user", "plain")), OpenRouterProtocol.toChatMessages(" plain "))
    }

    @Test fun `request json carries model stream flag and messages`() {
        val json = OpenRouterProtocol.buildRequestJson("openai/gpt-4o-mini", listOf(ChatTurn("user", "hi")), 256)
        val obj = org.json.JSONObject(json)
        assertEquals("openai/gpt-4o-mini", obj.getString("model"))
        assertTrue(obj.getBoolean("stream"))
        assertEquals(256, obj.getInt("max_tokens"))
        assertEquals("hi", obj.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `sse delta done and comments are parsed`() {
        val delta = OpenRouterProtocol.parseSseLine("""data: {"choices":[{"delta":{"content":"Bon"}}]}""")
        assertEquals(SseEvent.Delta("Bon"), delta)
        assertEquals(SseEvent.Done, OpenRouterProtocol.parseSseLine("data: [DONE]"))
        assertEquals(SseEvent.Ignore, OpenRouterProtocol.parseSseLine(": OPENROUTER PROCESSING"))
        assertEquals(SseEvent.Ignore, OpenRouterProtocol.parseSseLine(""))
        assertEquals(SseEvent.Ignore, OpenRouterProtocol.parseSseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
    }

    @Test fun `in-stream error raises IOException`() {
        try {
            OpenRouterProtocol.parseSseLine("""data: {"error":{"message":"boom"}}""")
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
    }

    @Test fun `free primary falls back to other free models only`() {
        val chain = OpenRouterProtocol.fallbackChain(
            "a/x:free", listOf("a/x:free", "b/y:free", "c/paid", "d/z:free", "e/w:free", "f/v:free"),
        )
        assertEquals(listOf("a/x:free", "b/y:free", "d/z:free", "e/w:free"), chain)
    }

    @Test fun `a paid primary is never silently replaced`() {
        assertEquals(listOf("openai/gpt-4o-mini"), OpenRouterProtocol.fallbackChain("openai/gpt-4o-mini", listOf("b/y:free")))
    }

    @Test fun `only saturation and availability errors are retryable`() {
        assertTrue(OpenRouterProtocol.isRetryable(429))
        assertTrue(OpenRouterProtocol.isRetryable(503))
        assertTrue(OpenRouterProtocol.isRetryable(404))
        assertFalse(OpenRouterProtocol.isRetryable(401))
        assertFalse(OpenRouterProtocol.isRetryable(402))
    }

    @Test fun `http errors are readable and never mention a key`() {
        val msg = OpenRouterProtocol.describeHttpError(401, """{"error":{"message":"No auth"}}""")
        assertTrue(msg.contains("401"))
        assertTrue(msg.contains("invalide"))
        assertFalse(msg.contains("Bearer"))
    }
}
