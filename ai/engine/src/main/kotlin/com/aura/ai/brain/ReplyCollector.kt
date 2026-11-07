package com.aura.ai.brain

import com.aura.ai.provider.ChatEvent
import com.aura.ai.provider.ChatMessage
import com.aura.ai.provider.ChatRole
import com.aura.ai.provider.FinishReason
import com.aura.ai.provider.ProviderException
import com.aura.ai.provider.TokenUsage
import kotlinx.coroutines.flow.Flow

data class CollectedReply(
    val text: String,
    val usage: TokenUsage?,
    val model: String?,
    val finishReason: FinishReason,
    val continuations: Int,
    /** Still cut short at the end (the continuation budget ran out, or the stream broke for good). */
    val truncated: Boolean,
    val filtered: Boolean,
    val contextExceeded: Boolean,
    val emptyAfterRetries: Boolean,
) {
    val usable: Boolean get() = text.isNotBlank() && !filtered && !contextExceeded
}

/**
 * Turns a model stream into one answer and copes with incomplete replies:
 *  - cut by the token limit (`length`) or by a dropped connection → ask the model to continue (bounded);
 *  - empty reply → one retry;
 *  - blocked by the provider's filter, or context overflow → reported, never silently swallowed.
 * It does not invent content: a still-truncated answer is flagged [CollectedReply.truncated].
 */
class ReplyCollector(
    private val maxContinuations: Int = 2,
    private val maxEmptyRetries: Int = 1,
) {
    suspend fun collect(
        messages: List<ChatMessage>,
        chat: (List<ChatMessage>) -> Flow<ChatEvent>,
        onDelta: suspend (String) -> Unit = {},
        onThinking: suspend () -> Unit = {},
    ): CollectedReply {
        val text = StringBuilder()
        var usage: TokenUsage? = null
        var model: String? = null
        var continuations = 0
        var emptyRetries = 0
        var request = messages
        while (true) {
            var reason = FinishReason.UNKNOWN
            var received = false
            try {
                chat(request).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> { received = true; text.append(ev.text); onDelta(ev.text) }
                        ChatEvent.Thinking -> onThinking()
                        is ChatEvent.Finished -> {
                            reason = ev.reason
                            ev.usage?.let { u -> usage = usage?.plus(u) ?: u }
                            ev.model?.let { model = it }
                        }
                    }
                }
            } catch (e: ProviderException) {
                // A failure after some text arrived is a truncation we can try to continue; otherwise it is the caller's problem.
                if (received && e.error.retryable) reason = FinishReason.NETWORK_ERROR else throw e
            }

            fun result(truncated: Boolean = false, filtered: Boolean = false, ctx: Boolean = false, empty: Boolean = false) =
                CollectedReply(text.toString(), usage, model, reason, continuations, truncated, filtered, ctx, empty)

            when {
                reason == FinishReason.FILTERED -> return result(filtered = true)
                reason == FinishReason.CONTEXT_EXCEEDED -> return result(ctx = true)
                (reason == FinishReason.LENGTH || reason == FinishReason.NETWORK_ERROR) && text.isNotBlank() -> {
                    if (continuations >= maxContinuations) return result(truncated = true)
                    continuations++
                    request = messages + ChatMessage(ChatRole.ASSISTANT, text.toString()) + ChatMessage(ChatRole.USER, CONTINUE)
                }
                text.isBlank() -> {
                    if (emptyRetries >= maxEmptyRetries) return result(empty = true)
                    emptyRetries++
                }
                else -> return result()
            }
        }
    }

    companion object {
        const val CONTINUE = "Continue exactly where you stopped. Do not repeat what you already wrote."
    }
}
