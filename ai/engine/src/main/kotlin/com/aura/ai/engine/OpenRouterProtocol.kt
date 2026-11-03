package com.aura.ai.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class ChatTurn(val role: String, val content: String)

internal sealed interface SseEvent {
    data class Delta(val text: String) : SseEvent
    data object Done : SseEvent
    data object Ignore : SseEvent
}

/** Pure (Android-free) helpers for the OpenRouter chat-completions protocol. */
internal object OpenRouterProtocol {
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    const val MODELS_ENDPOINT = "https://openrouter.ai/api/v1/models"

    private val TURN = Regex("<start_of_turn>(user|model)\\n(.*?)(?:<end_of_turn>|\\z)", RegexOption.DOT_MATCHES_ALL)

    /**
     * PromptBuilder emits Gemma-style turn markers. Convert them to chat messages;
     * blank turns are dropped and adjacent same-role turns merged (providers reject both).
     */
    fun toChatMessages(prompt: String): List<ChatTurn> {
        val turns = TURN.findAll(prompt)
            .map { ChatTurn(if (it.groupValues[1] == "model") "assistant" else "user", it.groupValues[2].trim()) }
            .filter { it.content.isNotEmpty() }
            .toList()
        if (turns.isEmpty()) return listOf(ChatTurn("user", prompt.trim()))
        val merged = ArrayList<ChatTurn>()
        for (t in turns) {
            val last = merged.lastOrNull()
            if (last != null && last.role == t.role) {
                merged[merged.size - 1] = last.copy(content = last.content + "\n\n" + t.content)
            } else merged.add(t)
        }
        return merged
    }

    fun buildRequestJson(model: String, turns: List<ChatTurn>, maxTokens: Int): String {
        val messages = JSONArray()
        turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        return JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", maxTokens)
            .put("messages", messages)
            .toString()
    }

    /** Parses one line of the server-sent-events stream. Throws [IOException] on an in-stream error. */
    fun parseSseLine(line: String): SseEvent {
        val l = line.trim()
        if (!l.startsWith("data:")) return SseEvent.Ignore // blank lines and ": OPENROUTER PROCESSING" comments
        val payload = l.removePrefix("data:").trim()
        if (payload == "[DONE]") return SseEvent.Done
        if (payload.isEmpty()) return SseEvent.Ignore
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return SseEvent.Ignore
        obj.optJSONObject("error")?.let { throw IOException(it.optString("message", "Erreur OpenRouter")) }
        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return SseEvent.Ignore
        if (delta.isNull("content")) return SseEvent.Ignore
        val text = delta.optString("content", "")
        return if (text.isEmpty()) SseEvent.Ignore else SseEvent.Delta(text)
    }

    /**
     * Free text models from the public `/models` payload: both prices are zero, text in/out only,
     * no safety/guard models, not already expired. Sorted by name.
     */
    fun parseFreeModels(json: String, today: String = java.time.LocalDate.now().toString()): List<OpenRouterModel> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OpenRouterModel>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue
            val pricing = m.optJSONObject("pricing") ?: continue
            if (pricing.optString("prompt", "").toDoubleOrNull() != 0.0 ||
                pricing.optString("completion", "").toDoubleOrNull() != 0.0) continue
            val arch = m.optJSONObject("architecture")
            val outputs = strings(arch?.optJSONArray("output_modalities") ?: m.optJSONArray("output_modalities"))
            val inputs = strings(arch?.optJSONArray("input_modalities") ?: m.optJSONArray("input_modalities"))
            if (outputs.isNotEmpty() && outputs != listOf("text")) continue
            if (inputs.isNotEmpty() && "text" !in inputs) continue
            val lower = id.lowercase()
            if ("safety" in lower || "guard" in lower) continue
            val expires = if (m.isNull("expiration_date")) null else m.optString("expiration_date").takeIf { it.isNotBlank() }
            if (expires != null && expires <= today) continue
            out += OpenRouterModel(id, m.optString("name", id), m.optInt("context_length", 0), expires)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }

    /** Human-readable error; never includes the API key. */
    fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        val base = when (code) {
            401 -> "Clé API OpenRouter invalide ou révoquée"
            402 -> "Crédits OpenRouter insuffisants"
            404 -> "Modèle introuvable ou indisponible"
            429 -> "Limite de requêtes OpenRouter atteinte"
            else -> "Erreur OpenRouter"
        }
        return "$base (HTTP $code)" + if (!detail.isNullOrBlank() && detail != "null") " : $detail" else ""
    }
}
