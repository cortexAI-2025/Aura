package com.aura.ai.brain

import com.aura.ai.provider.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReplyCollectorTest {
    private val base = listOf(ChatMessage(ChatRole.USER, "Raconte"))

    private sealed interface Turn {
        data class Say(val text: String, val reason: FinishReason, val usage: TokenUsage? = null) : Turn
        data class SayThenFail(val text: String, val error: ProviderError) : Turn
        data class Fail(val error: ProviderError) : Turn
    }

    private class Script(private val turns: List<Turn>) {
        private var i = 0
        val requests = ArrayList<List<ChatMessage>>()
        fun chat(messages: List<ChatMessage>): Flow<ChatEvent> = flow {
            requests += messages
            when (val t = turns[i++]) {
                is Turn.Say -> {
                    if (t.text.isNotEmpty()) emit(ChatEvent.Delta(t.text))
                    emit(ChatEvent.Finished(t.reason, t.usage, "m"))
                }
                is Turn.SayThenFail -> { emit(ChatEvent.Delta(t.text)); throw ProviderException(t.error) }
                is Turn.Fail -> throw ProviderException(t.error)
            }
        }
    }

    private fun run(script: Script, collector: ReplyCollector = ReplyCollector(), deltas: MutableList<String> = mutableListOf()) =
        runBlocking { collector.collect(base, script::chat, onDelta = { deltas += it }) }

    @Test fun `a normal reply passes through with usage`() {
        val s = Script(listOf(Turn.Say("Bonjour", FinishReason.STOP, TokenUsage(5, 2, 7))))
        val deltas = mutableListOf<String>()
        val r = run(s, deltas = deltas)
        assertEquals("Bonjour", r.text)
        assertEquals(listOf("Bonjour"), deltas)
        assertEquals(TokenUsage(5, 2, 7), r.usage)
        assertTrue(r.usable); assertFalse(r.truncated); assertEquals(0, r.continuations)
    }

    @Test fun `a reply cut by the token limit is continued and usage is summed`() {
        val s = Script(listOf(
            Turn.Say("Il était une fois", FinishReason.LENGTH, TokenUsage(10, 5)),
            Turn.Say(" un chat.", FinishReason.STOP, TokenUsage(20, 3)),
        ))
        val r = run(s)
        assertEquals("Il était une fois un chat.", r.text)
        assertEquals(1, r.continuations)
        assertEquals(TokenUsage(30, 8, 38), r.usage)
        val second = s.requests[1]
        assertEquals(ChatRole.ASSISTANT, second[second.size - 2].role)
        assertEquals("Il était une fois", second[second.size - 2].content)
        assertEquals(ReplyCollector.CONTINUE, second.last().content)
    }

    @Test fun `continuations are bounded and the result is flagged truncated`() {
        val s = Script(List(3) { Turn.Say("blah ", FinishReason.LENGTH) })
        val r = run(s, ReplyCollector(maxContinuations = 2))
        assertTrue(r.truncated)
        assertEquals(2, r.continuations)
        assertEquals(3, s.requests.size)
        assertEquals("blah blah blah ", r.text)
    }

    @Test fun `a dropped connection after some text is continued`() {
        val s = Script(listOf(
            Turn.SayThenFail("Bon", ProviderError(ErrorKind.NETWORK)),
            Turn.Say("jour", FinishReason.STOP),
        ))
        val r = run(s)
        assertEquals("Bonjour", r.text)
        assertEquals(1, r.continuations)
    }

    @Test fun `a truncated stream flagged network_error is continued too`() {
        val s = Script(listOf(Turn.Say("Bon", FinishReason.NETWORK_ERROR), Turn.Say("jour", FinishReason.STOP)))
        assertEquals("Bonjour", run(s).text)
    }

    @Test fun `an empty reply is retried once then reported`() {
        val ok = run(Script(listOf(Turn.Say("", FinishReason.STOP), Turn.Say("Salut", FinishReason.STOP))))
        assertEquals("Salut", ok.text)
        val bad = run(Script(listOf(Turn.Say("", FinishReason.STOP), Turn.Say("  ", FinishReason.STOP))))
        assertTrue(bad.emptyAfterRetries); assertFalse(bad.usable)
    }

    @Test fun `a filtered reply is reported and never continued`() {
        val s = Script(listOf(Turn.Say("début", FinishReason.FILTERED)))
        val r = run(s)
        assertTrue(r.filtered); assertFalse(r.usable)
        assertEquals(1, s.requests.size)
    }

    @Test fun `context overflow is reported for the caller to trim`() {
        val r = run(Script(listOf(Turn.Say("", FinishReason.CONTEXT_EXCEEDED))))
        assertTrue(r.contextExceeded); assertFalse(r.usable)
    }

    @Test fun `a provider failure with no text is propagated`() {
        try {
            run(Script(listOf(Turn.Fail(ProviderError(ErrorKind.INVALID_KEY)))))
            fail()
        } catch (e: ProviderException) { assertEquals(ErrorKind.INVALID_KEY, e.error.kind) }
    }

    @Test fun `a non retryable failure after some text is propagated too`() {
        try {
            run(Script(listOf(Turn.SayThenFail("Bon", ProviderError(ErrorKind.INVALID_KEY)))))
            fail()
        } catch (e: ProviderException) { assertEquals(ErrorKind.INVALID_KEY, e.error.kind) }
    }

    @Test fun `thinking is forwarded`() {
        var thinking = 0
        val chat = { _: List<ChatMessage> -> flow<ChatEvent> { emit(ChatEvent.Thinking); emit(ChatEvent.Delta("x")); emit(ChatEvent.Finished(FinishReason.STOP, null, null)) } }
        runBlocking { ReplyCollector().collect(base, chat, onThinking = { thinking++ }) }
        assertEquals(1, thinking)
    }
}
