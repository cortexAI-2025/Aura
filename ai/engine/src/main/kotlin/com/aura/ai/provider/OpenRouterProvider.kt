package com.aura.ai.provider

import com.aura.ai.engine.OpenRouterCatalog
import com.aura.ai.engine.OpenRouterProtocol
import org.json.JSONObject

/** OpenRouter (already shipped in v0.1), now on the common [AIProvider] contract. */
class OpenRouterProvider(
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 120_000,
    allowInsecureLoopback: Boolean = false,
) : OpenAiCompatibleProvider(connectTimeoutMs, readTimeoutMs, allowInsecureLoopback) {

    override val id = ID
    override val displayName = "OpenRouter"
    override val defaultBaseUrl = "https://openrouter.ai/api/v1"
    override val defaultModel = DEFAULT_MODEL
    override val capabilities = ProviderCapabilities(streaming = true, reportsUsage = true, thinkingToggle = false)
    override val keyHint = "Clé API OpenRouter (openrouter.ai/keys). Les modèles « :free » sont gratuits mais souvent saturés."
    override val dataNotice =
        "Vos messages et les extraits autorisés sont envoyés à OpenRouter, qui les transmet au fournisseur du modèle " +
            "choisi. Chaque modèle a sa propre politique de données : une clé personnelle ne garantit pas la confidentialité."
    override val suggestedModels = OpenRouterCatalog.FALLBACK.map {
        ModelInfo(it.id, it.name, it.contextLength.takeIf { c -> c > 0 }, free = it.id.endsWith(":free"))
    }

    override fun buildBody(request: ChatRequest, stream: Boolean): JSONObject =
        JSONObject().apply {
            put("model", request.model)
            put("messages", messagesJson(request))
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
        }

    override fun extraHeaders() = mapOf(
        "HTTP-Referer" to "https://github.com/cortexAI-2025/Aura",
        "X-Title" to "Aura",
    )

    override fun mapError(httpStatus: Int?, body: String?): ProviderError {
        val detail = try { JSONObject(body.orEmpty()).optJSONObject("error")?.strOrNull("message") } catch (e: Exception) { null }
        val kind = when (httpStatus) {
            401 -> ErrorKind.INVALID_KEY
            402 -> ErrorKind.INSUFFICIENT_BALANCE
            403 -> ErrorKind.PERMISSION_DENIED
            404 -> ErrorKind.MODEL_NOT_FOUND
            408 -> ErrorKind.TIMEOUT
            400 -> ErrorKind.BAD_REQUEST
            429 -> ErrorKind.RATE_LIMITED
            502, 503 -> ErrorKind.OVERLOADED
            in 500..599 -> ErrorKind.SERVER_ERROR
            else -> ErrorKind.UNKNOWN
        }
        return ProviderError(kind, httpStatus, detail = detail?.let { Redactor.redact(it) })
    }

    /** Only a FREE primary model gets (free) fallbacks: a paid model is never silently replaced. */
    override fun fallbackModels(model: String): List<String> =
        OpenRouterProtocol.fallbackChain(model, OpenRouterCatalog.FALLBACK.map { it.id }).drop(1)

    companion object {
        const val ID = "openrouter"
        const val DEFAULT_MODEL = "google/gemma-4-31b-it:free"
    }
}
