package com.aura.ai.provider

import org.junit.Assert.*
import org.junit.Test

class OpenAiStreamParserTest {
    private fun parser() = OpenAiStreamParser { status, body -> ZaiErrors.map(status, body) }

    @Test fun `plain content deltas and final usage chunk`() {
        val p = parser()
        val events = listOf(
            """data: {"id":"1","model":"glm-4.7-flash","choices":[{"index":0,"delta":{"content":"Bon"},"finish_reason":null}]}""",
            """data: {"id":"1","choices":[{"index":0,"delta":{"content":"jour"},"finish_reason":null}]}""",
            """data: {"id":"1","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":8,"completion_tokens":262,"total_tokens":270}}""",
            "data: [DONE]",
        ).flatMap { p.feed(it) }
        assertEquals(listOf<ChatEvent>(ChatEvent.Delta("Bon"), ChatEvent.Delta("jour")), events)
        assertTrue(p.done)
        val fin = p.finish()
        assertEquals(FinishReason.STOP, fin.reason)
        assertEquals(TokenUsage(8, 262, 270), fin.usage)
        assertEquals("glm-4.7-flash", fin.model)
    }

    @Test fun `reasoning is hidden and signalled once`() {
        val p = parser()
        val events = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"Let me think"}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":" more"}}]}""",
            """data: {"choices":[{"delta":{"content":"42"}}]}""",
        ).flatMap { p.feed(it) }
        assertEquals(listOf(ChatEvent.Thinking, ChatEvent.Delta("42")), events)
    }

    @Test fun `comments, blank lines and json null are ignored`() {
        val p = parser()
        assertTrue(p.feed(": OPENROUTER PROCESSING").isEmpty())
        assertTrue(p.feed("").isEmpty())
        assertTrue(p.feed("event: message").isEmpty())
        assertTrue(p.feed("""data: {"choices":[{"delta":{"content":null},"finish_reason":null}]}""").isEmpty())
        assertTrue(p.feed("data: not-json").isEmpty())
    }

    @Test fun `usage-only chunk with empty choices is handled`() {
        val p = parser()
        p.feed("""data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}""")
        p.feed("data: [DONE]")
        assertEquals(TokenUsage(3, 4, 7), p.finish().usage)
    }

    @Test fun `finish reasons documented by Z ai are mapped`() {
        mapOf(
            "length" to FinishReason.LENGTH, "sensitive" to FinishReason.FILTERED,
            "model_context_window_exceeded" to FinishReason.CONTEXT_EXCEEDED,
            "network_error" to FinishReason.NETWORK_ERROR, "tool_calls" to FinishReason.TOOL_CALLS,
            "stop" to FinishReason.STOP, "weird" to FinishReason.UNKNOWN,
        ).forEach { (wire, expected) ->
            val p = parser()
            p.feed("""data: {"choices":[{"delta":{},"finish_reason":"$wire"}]}""")
            p.feed("data: [DONE]")
            assertEquals(wire, expected, p.finish().reason)
        }
    }

    @Test fun `closing without finish_reason or DONE flags a truncated stream`() {
        val p = parser()
        p.feed("""data: {"choices":[{"delta":{"content":"Bon"}}]}""")
        assertEquals(FinishReason.NETWORK_ERROR, p.finish().reason)
    }

    @Test fun `in-stream error object becomes a provider exception`() {
        val p = parser()
        try {
            p.feed("""data: {"error":{"code":"1302","message":"Rate limit reached for requests"}}""")
            fail("expected exception")
        } catch (e: ProviderException) {
            assertEquals(ErrorKind.RATE_LIMITED, e.error.kind)
            assertEquals("1302", e.error.serverCode)
        }
    }
}
