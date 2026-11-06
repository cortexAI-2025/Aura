package com.aura.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResilientChatTest {
    private sealed interface Step {
        data class Fail(val error: ProviderError) : Step
        data class Ok(val text: String) : Step
        data class PartialThenFail(val text: String, val error: ProviderError) : Step
    }

    private class FakeProvider(steps: List<Step>, private val fallbacks: List<String> = emptyList()) : AIProvider {
        private val queue = ArrayDeque(steps)
        val modelsCalled = ArrayList<String>()
        override val id = "fake"; override val displayName = "Fake"; override val defaultBaseUrl = "https://x"
        override val defaultModel = "m1"; override val suggestedModels = emptyList<ModelInfo>()
        override val capabilities = ProviderCapabilities(); override val keyHint: String? = null; override val dataNotice = ""
        override fun fallbackModels(model: String) = fallbacks
        override fun streamChat(config: ProviderConfig, request: ChatRequest): Flow<ChatEvent> = flow {
            modelsCalled += request.model
            when (val s = queue.removeFirst()) {
                is Step.Fail -> throw ProviderException(s.error)
                is Step.Ok -> { emit(ChatEvent.Delta(s.text)); emit(ChatEvent.Finished(FinishReason.STOP, null, request.model)) }
                is Step.PartialThenFail -> { emit(ChatEvent.Delta(s.text)); throw ProviderException(s.error) }
            }
        }
        override suspend fun complete(config: ProviderConfig, request: ChatRequest) = error("unused")
        override suspend fun testConnection(config: ProviderConfig) = error("unused")
    }

    private val cfg = ProviderConfig("k-123456", "https://x", "m1")
    private val req = ChatRequest("m1", listOf(ChatMessage(ChatRole.USER, "hi")))
    private fun err(kind: ErrorKind, retryAfterMs: Long? = null) = ProviderError(kind, retryAfterMs = retryAfterMs)
    private fun chat(delays: MutableList<Long>) = ResilientChat(maxRetriesPerModel = 2, sleep = { delays += it })

    private fun run(p: AIProvider, chat: ResilientChat, allowFallback: Boolean = true) =
        runBlocking { chat.stream(p, cfg, req, allowFallback).toList() }

    @Test fun `retries a rate limit with growing delays then succeeds`() {
        val delays = mutableListOf<Long>()
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.RATE_LIMITED)), Step.Fail(err(ErrorKind.RATE_LIMITED)), Step.Ok("salut")))
        val events = run(p, chat(delays))
        assertEquals("salut", (events.first() as ChatEvent.Delta).text)
        assertEquals(listOf(1_000L, 2_000L), delays)
        assertEquals(listOf("m1", "m1", "m1"), p.modelsCalled)
    }

    @Test fun `Retry-After from the server wins over the default delay`() {
        val delays = mutableListOf<Long>()
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.RATE_LIMITED, retryAfterMs = 7_000)), Step.Ok("ok")))
        run(p, chat(delays))
        assertEquals(listOf(7_000L), delays)
    }

    @Test fun `a bad key is never retried`() {
        val delays = mutableListOf<Long>()
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.INVALID_KEY)), Step.Ok("never")))
        try { run(p, chat(delays)); fail() } catch (e: ProviderException) { assertEquals(ErrorKind.INVALID_KEY, e.error.kind) }
        assertEquals(1, p.modelsCalled.size)
        assertTrue(delays.isEmpty())
    }

    @Test fun `no balance is not retried`() {
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.INSUFFICIENT_BALANCE))))
        try { run(p, chat(mutableListOf())); fail() } catch (e: ProviderException) { assertEquals(ErrorKind.INSUFFICIENT_BALANCE, e.error.kind) }
        assertEquals(1, p.modelsCalled.size)
    }

    @Test fun `switches to the fallback model once retries are exhausted`() {
        val delays = mutableListOf<Long>()
        val steps = List(3) { Step.Fail(err(ErrorKind.OVERLOADED)) } + Step.Ok("via fallback")
        val p = FakeProvider(steps, fallbacks = listOf("m2"))
        val events = run(p, chat(delays))
        assertEquals("via fallback", (events.first() as ChatEvent.Delta).text)
        assertEquals(listOf("m1", "m1", "m1", "m2"), p.modelsCalled)
        assertEquals("m2", (events.last() as ChatEvent.Finished).model)
    }

    @Test fun `an unknown model switches immediately when a fallback exists`() {
        val delays = mutableListOf<Long>()
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.MODEL_NOT_FOUND)), Step.Ok("ok")), fallbacks = listOf("m2"))
        run(p, chat(delays))
        assertEquals(listOf("m1", "m2"), p.modelsCalled)
        assertTrue(delays.isEmpty())
    }

    @Test fun `an unknown model without fallback surfaces`() {
        val p = FakeProvider(listOf(Step.Fail(err(ErrorKind.MODEL_NOT_FOUND))))
        try { run(p, chat(mutableListOf())); fail() } catch (e: ProviderException) { assertEquals(ErrorKind.MODEL_NOT_FOUND, e.error.kind) }
    }

    @Test fun `without fallback the last error is thrown after the retries`() {
        val steps = List(3) { Step.Fail(err(ErrorKind.RATE_LIMITED)) }
        val p = FakeProvider(steps)
        try { run(p, chat(mutableListOf())); fail() } catch (e: ProviderException) { assertEquals(ErrorKind.RATE_LIMITED, e.error.kind) }
        assertEquals(3, p.modelsCalled.size)
    }

    @Test fun `fallback can be disabled`() {
        val steps = List(3) { Step.Fail(err(ErrorKind.RATE_LIMITED)) } + Step.Ok("x")
        val p = FakeProvider(steps, fallbacks = listOf("m2"))
        try { run(p, chat(mutableListOf()), allowFallback = false); fail() } catch (e: ProviderException) { }
        assertFalse(p.modelsCalled.contains("m2"))
    }

    @Test fun `after partial text nothing is retried`() {
        val p = FakeProvider(listOf(Step.PartialThenFail("Bon", err(ErrorKind.NETWORK)), Step.Ok("again")))
        val seen = mutableListOf<ChatEvent>()
        try {
            runBlocking { chat(mutableListOf()).stream(p, cfg, req).collect { seen += it } }
            fail()
        } catch (e: ProviderException) { assertEquals(ErrorKind.NETWORK, e.error.kind) }
        assertEquals(listOf<ChatEvent>(ChatEvent.Delta("Bon")), seen)
        assertEquals(1, p.modelsCalled.size)
    }
}
