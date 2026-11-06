package com.aura.ai.provider

import org.json.JSONObject

/** `null` for JSON null *or* a missing key (Android's optString would return the text "null"). */
internal fun JSONObject.strOrNull(name: String): String? = if (isNull(name)) null else optString(name, "")

/**
 * Parses the server-sent events of an OpenAI-style `/chat/completions` stream (also what Z.ai sends).
 * Feed it one line at a time. Reasoning text (`reasoning_content` / `reasoning`) is discarded: only a
 * single [ChatEvent.Thinking] marker is produced.
 */
internal class OpenAiStreamParser(
    private val mapError: (httpStatus: Int?, body: String?) -> ProviderError,
) {
    private var finish: FinishReason? = null
    private var usage: TokenUsage? = null
    private var model: String? = null
    private var thinkingSignalled = false

    /** True once `data: [DONE]` was seen. */
    var done = false
        private set

    fun feed(rawLine: String): List<ChatEvent> {
        val line = rawLine.trim()
        // Blank lines, `event:` lines and `: comment` keep-alives carry no data.
        if (!line.startsWith("data:")) return emptyList()
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") { done = true; return emptyList() }

        val obj = try { JSONObject(payload) } catch (e: Exception) { return emptyList() }
        if (obj.has("error") && !obj.isNull("error")) throw ProviderException(mapError(null, payload))

        obj.strOrNull("model")?.takeIf { it.isNotBlank() }?.let { model = it }
        obj.optJSONObject("usage")?.let { u ->
            val p = u.optInt("prompt_tokens", 0)
            val c = u.optInt("completion_tokens", 0)
            usage = TokenUsage(p, c, u.optInt("total_tokens", p + c))
        }

        val events = ArrayList<ChatEvent>(2)
        val choice = obj.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val reasoning = delta.strOrNull("reasoning_content") ?: delta.strOrNull("reasoning")
                if (!reasoning.isNullOrEmpty() && !thinkingSignalled) {
                    thinkingSignalled = true
                    events += ChatEvent.Thinking
                }
                val text = delta.strOrNull("content")
                if (!text.isNullOrEmpty()) events += ChatEvent.Delta(text)
            }
            choice.strOrNull("finish_reason")?.takeIf { it.isNotBlank() }?.let { finish = FinishReason.fromWire(it) }
        }
        return events
    }

    /**
     * The terminal event. If the connection closed without a `finish_reason` nor `[DONE]` the answer
     * is flagged [FinishReason.NETWORK_ERROR] so the caller can treat it as truncated.
     */
    fun finish(): ChatEvent.Finished {
        val reason = finish ?: if (done) FinishReason.STOP else FinishReason.NETWORK_ERROR
        return ChatEvent.Finished(reason, usage, model)
    }
}
