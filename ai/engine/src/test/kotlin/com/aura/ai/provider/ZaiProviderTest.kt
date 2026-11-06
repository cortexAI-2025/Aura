package com.aura.ai.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ZaiProviderTest {
    private val key = "0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMnOp"
    private val server = FakeServer()
    private val provider = ZaiProvider(connectTimeoutMs = 2_000, readTimeoutMs = 1_500, allowInsecureLoopback = true)
    private val config get() = ProviderConfig(key, server.baseUrl, "glm-4.7-flash")
    private fun request(thinking: Boolean = false) =
        ChatRequest("glm-4.7-flash", listOf(ChatMessage(ChatRole.SYSTEM, "Tu es Aura."), ChatMessage(ChatRole.USER, "Bonjour")), maxTokens = 256, thinking = thinking)

    @After fun tearDown() = server.close()

    private val okBody = """{"id":"x","model":"glm-4.7-flash","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}"""

    // ── connection test ──────────────────────────────────────────────────────

    @Test fun `test connection succeeds and reports model latency and usage`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 200, okBody) }
        val r = provider.testConnection(config) as ConnectionResult.Success
        assertEquals("glm-4.7-flash", r.model)
        assertEquals("OK", r.replyPreview)
        assertEquals(TokenUsage(9, 2, 11), r.usage)
        assertTrue(r.latencyMs >= 0)
    }

    @Test fun `request targets chat completions with the documented shape`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 200, okBody) }
        provider.testConnection(config)
        val rec = server.requests.single()
        assertEquals("POST", rec.method)
        assertEquals("/api/paas/v4/chat/completions", rec.path)
        assertEquals("Bearer $key", rec.headers["authorization"])
        assertTrue(rec.headers["content-type"]!!.startsWith("application/json"))
        val body = JSONObject(rec.body)
        assertEquals("glm-4.7-flash", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertFalse("the key must only travel in the header", rec.body.contains(key))
    }

    @Test fun `thinking can be switched on explicitly`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 200, okBody) }
        provider.complete(config, request(thinking = true))
        assertEquals("enabled", JSONObject(server.requests.single().body).getJSONObject("thinking").getString("type"))
    }

    @Test fun `invalid key is reported without ever echoing the key`() = runBlocking {
        server.handler = { ex ->
            server.respond(ex, 401, """{"error":{"code":"1000","message":"Authentication Failed for $key"}}""")
        }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(ErrorKind.INVALID_KEY, r.error.kind)
        assertEquals(401, r.error.httpStatus)
        assertFalse(r.error.userMessage.contains(key))
        assertFalse(r.error.toString().contains(key))
    }

    @Test fun `unknown model id is explained`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 400, """{"error":{"code":"1211","message":"Unknown Model"}}""") }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(ErrorKind.MODEL_NOT_FOUND, r.error.kind)
        assertTrue(r.error.userMessage.contains("glm-4.7-flash"))
    }

    @Test fun `rate limit is retryable and honours Retry-After`() = runBlocking {
        server.handler = { ex ->
            server.respond(ex, 429, """{"error":{"code":"1302","message":"Rate limit reached for requests"}}""", mapOf("Retry-After" to "3"))
        }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(ErrorKind.RATE_LIMITED, r.error.kind)
        assertTrue(r.error.retryable)
        assertEquals(3_000L, r.error.retryAfterMs)
    }

    @Test fun `insufficient balance is a 429 that is not retryable`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 429, """{"error":{"code":"1113","message":"Insufficient balance"}}""") }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(ErrorKind.INSUFFICIENT_BALANCE, r.error.kind)
        assertFalse(r.error.retryable)
    }

    @Test fun `server error 500 is retryable`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 500, """{"error":{"code":"1234","message":"Network error"}}""") }
        assertTrue((provider.testConnection(config) as ConnectionResult.Failure).error.retryable)
    }

    @Test fun `a hanging server yields a timeout`() = runBlocking {
        server.handler = { Thread.sleep(2_500) }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(ErrorKind.TIMEOUT, r.error.kind)
        assertTrue(r.error.retryable)
    }

    @Test fun `unreachable host is a network error`() = runBlocking {
        val dead = ProviderConfig(key, "http://127.0.0.1:1/api/paas/v4", "glm-4.7-flash")
        val r = provider.testConnection(dead) as ConnectionResult.Failure
        assertEquals(ErrorKind.NETWORK, r.error.kind)
    }

    @Test fun `redirects are never followed so the key cannot leak to another host`() = runBlocking {
        server.handler = { ex -> ex.responseHeaders.add("Location", "http://127.0.0.1:1/steal"); ex.sendResponseHeaders(302, -1) }
        val r = provider.testConnection(config) as ConnectionResult.Failure
        assertEquals(1, server.requests.size)
        assertNotEquals(ErrorKind.NETWORK, r.error.kind) // we got the 302 itself, we did not chase it
    }

    @Test fun `plain http to a real host is refused before any request`() = runBlocking {
        val insecure = ProviderConfig(key, "http://api.z.ai/api/paas/v4", "glm-4.7-flash")
        val strict = ZaiProvider() // loopback exception NOT enabled
        val r = strict.testConnection(insecure) as ConnectionResult.Failure
        assertEquals(ErrorKind.INSECURE_ENDPOINT, r.error.kind)
    }

    @Test fun `missing key is refused locally`() = runBlocking {
        val r = provider.testConnection(config.copy(apiKey = " ")) as ConnectionResult.Failure
        assertEquals(ErrorKind.INVALID_KEY, r.error.kind)
        assertTrue(server.requests.isEmpty())
    }

    // ── streaming ────────────────────────────────────────────────────────────

    @Test fun `streaming yields thinking marker, text deltas and a final event with usage`() = runBlocking {
        server.handler = { ex ->
            server.sse(ex, listOf(
                """data: {"id":"1","model":"glm-4.7-flash","choices":[{"index":0,"delta":{"reasoning_content":"Hmm"},"finish_reason":null}]}""",
                """data: {"id":"1","choices":[{"index":0,"delta":{"content":"Bon"},"finish_reason":null}]}""",
                """data: {"id":"1","choices":[{"index":0,"delta":{"content":"jour !"},"finish_reason":null}]}""",
                """data: {"id":"1","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":""}}],"usage":{"prompt_tokens":8,"completion_tokens":5,"total_tokens":13}}""",
                "data: [DONE]",
            ))
        }
        val events = provider.streamChat(config, request(thinking = true)).toList()
        assertEquals(ChatEvent.Thinking, events[0])
        assertEquals("Bonjour !", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
        val fin = events.last() as ChatEvent.Finished
        assertEquals(FinishReason.STOP, fin.reason)
        assertEquals(TokenUsage(8, 5, 13), fin.usage)
        assertEquals("glm-4.7-flash", fin.model)
        val sent = JSONObject(server.requests.single().body)
        assertTrue(sent.getBoolean("stream"))
        assertEquals("text/event-stream", server.requests.single().headers["accept"])
    }

    @Test fun `a stream cut before the end is flagged as truncated`() = runBlocking {
        server.handler = { ex ->
            server.sse(ex, listOf("""data: {"choices":[{"index":0,"delta":{"content":"Bon"},"finish_reason":null}]}"""))
        }
        val events = provider.streamChat(config, request()).toList()
        assertEquals("Bon", (events.first() as ChatEvent.Delta).text)
        assertEquals(FinishReason.NETWORK_ERROR, (events.last() as ChatEvent.Finished).reason)
    }

    @Test fun `length finish reason is surfaced`() = runBlocking {
        server.handler = { ex ->
            server.sse(ex, listOf(
                """data: {"choices":[{"delta":{"content":"abc"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"length"}]}""",
                "data: [DONE]",
            ))
        }
        assertEquals(FinishReason.LENGTH, (provider.streamChat(config, request()).toList().last() as ChatEvent.Finished).reason)
    }

    @Test fun `http error on a stream throws a classified exception`() = runBlocking {
        server.handler = { ex -> server.respond(ex, 401, """{"error":{"code":"1001","message":"missing"}}""") }
        try {
            provider.streamChat(config, request()).toList()
            fail("expected exception")
        } catch (e: ProviderException) {
            assertEquals(ErrorKind.INVALID_KEY, e.error.kind)
            assertFalse(e.message!!.contains(key))
        }
    }

    // ── contract ─────────────────────────────────────────────────────────────

    @Test fun `provider metadata matches the verified documentation`() {
        assertEquals("zai", provider.id)
        assertEquals("https://api.z.ai/api/paas/v4", provider.defaultBaseUrl)
        assertEquals("glm-4.7-flash", provider.defaultModel)
        assertTrue(provider.suggestedModels.first { it.id == "glm-4.7-flash" }.free)
        assertFalse("no public balance endpoint is documented", provider.capabilities.balanceApi)
        assertTrue(provider.keyHint!!.contains("Standard"))
        assertTrue(provider.dataNotice.contains("confidentialité"))
    }

    @Test fun `a free glm model is never replaced silently`() {
        assertTrue(provider.fallbackModels("glm-4.7-flash").isEmpty())
    }
}
