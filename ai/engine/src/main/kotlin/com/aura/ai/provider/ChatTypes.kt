package com.aura.ai.provider

/** Provider-neutral chat primitives. No Android dependency: this package is unit-tested on the plain JVM. */

enum class ChatRole(val wire: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Ask the model to reason before answering (providers that support it). Off by default: faster, cheaper. */
    val thinking: Boolean = false,
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens,
) {
    operator fun plus(o: TokenUsage) = TokenUsage(
        promptTokens + o.promptTokens, completionTokens + o.completionTokens, totalTokens + o.totalTokens,
    )

    companion object { val ZERO = TokenUsage() }
}

enum class FinishReason {
    STOP, LENGTH, FILTERED, CONTEXT_EXCEEDED, NETWORK_ERROR, TOOL_CALLS, UNKNOWN;

    companion object {
        /** Values documented by Z.ai (`stop`, `tool_calls`, `length`, `sensitive`, `model_context_window_exceeded`, `network_error`) plus OpenAI's `content_filter`. */
        fun fromWire(raw: String?): FinishReason = when (raw?.lowercase()) {
            "stop", "end_turn" -> STOP
            "length", "max_tokens" -> LENGTH
            "sensitive", "content_filter" -> FILTERED
            "model_context_window_exceeded" -> CONTEXT_EXCEEDED
            "network_error" -> NETWORK_ERROR
            "tool_calls", "function_call" -> TOOL_CALLS
            else -> UNKNOWN
        }
    }
}

sealed interface ChatEvent {
    /** An incremental piece of the visible answer. */
    data class Delta(val text: String) : ChatEvent

    /** The model is reasoning; its reasoning text is never exposed. Emitted at most once per stream. */
    data object Thinking : ChatEvent

    /** Always the last event of a successful stream. [model] is the model that really answered. */
    data class Finished(val reason: FinishReason, val usage: TokenUsage?, val model: String?) : ChatEvent
}

data class ChatResult(
    val text: String,
    val finishReason: FinishReason,
    val usage: TokenUsage?,
    val model: String?,
    val latencyMs: Long,
)

sealed interface ConnectionResult {
    data class Success(
        val model: String?,
        val latencyMs: Long,
        val usage: TokenUsage?,
        val replyPreview: String,
    ) : ConnectionResult

    data class Failure(val error: ProviderError) : ConnectionResult
}
