package com.aura.ai.provider

import org.json.JSONObject

/**
 * Z.ai (Zhipu) — GLM models. Verified against docs.z.ai (Nov 2026):
 *  - endpoint `POST https://api.z.ai/api/paas/v4/chat/completions`, `Authorization: Bearer <Standard API key>`
 *  - `thinking.type` = enabled | disabled (enabled by default on GLM-4.7), stream via `stream: true`
 *  - GLM-4.7-Flash is listed as free (200K context, 128K max output).
 * NOT verified in the docs: the literal id `glm-4.7-flash` (derived from the documented `glm-4.7`); the
 * connection test reveals a wrong id (error 1211 "Unknown Model").
 */
class ZaiProvider(
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 120_000,
    allowInsecureLoopback: Boolean = false,
) : OpenAiCompatibleProvider(connectTimeoutMs, readTimeoutMs, allowInsecureLoopback) {

    override val id = ID
    override val displayName = "Z.ai (GLM)"
    override val defaultBaseUrl = DEFAULT_BASE_URL
    override val defaultModel = DEFAULT_MODEL
    override val capabilities = ProviderCapabilities(streaming = true, reportsUsage = true, thinkingToggle = true, balanceApi = false)
    override val keyHint =
        "Créez une clé API « Standard » sur z.ai (API Keys). Une clé GLM Coding Plan n'est pas valable ici : " +
            "autre endpoint et autre facturation."
    override val dataNotice =
        "Vos messages, les extraits de mémoire et les objectifs que vous autorisez sont envoyés à Z.ai " +
            "(api.z.ai) pour générer la réponse. Leur traitement dépend de la politique de confidentialité de Z.ai : " +
            "une clé personnelle ne garantit pas, à elle seule, la confidentialité chez le fournisseur."

    override val suggestedModels = listOf(
        ModelInfo(DEFAULT_MODEL, "GLM-4.7-Flash (gratuit)", 200_000, free = true,
            note = "Gratuit selon la page de prix Z.ai. Identifiant non cité tel quel dans la doc : confirmé par le test de connexion."),
        ModelInfo("glm-4.7", "GLM-4.7", 200_000, free = false, note = "Payant à l'usage."),
        ModelInfo("glm-5.3", "GLM-5.3", null, free = false, note = "Payant à l'usage."),
    )

    override fun buildBody(request: ChatRequest, stream: Boolean): JSONObject =
        JSONObject().apply {
            put("model", request.model)
            put("messages", messagesJson(request))
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            // Thinking is ON by default on GLM-4.7: always state our choice explicitly.
            put("thinking", JSONObject().put("type", if (request.thinking) "enabled" else "disabled"))
        }

    override fun extraHeaders() = mapOf("Accept-Language" to "en-US,en")

    override fun mapError(httpStatus: Int?, body: String?): ProviderError = ZaiErrors.map(httpStatus, body)

    companion object {
        const val ID = "zai"
        const val DEFAULT_BASE_URL = "https://api.z.ai/api/paas/v4"
        const val DEFAULT_MODEL = "glm-4.7-flash"
    }
}

/** Z.ai business error codes (docs.z.ai/api-reference/api-code) mapped to [ProviderError]. */
internal object ZaiErrors {
    private const val KEY_HINT =
        "Utilisez une clé API « Standard » (z.ai > API Keys) ; une clé Coding Plan ne fonctionne pas sur api.z.ai/api/paas/v4."

    fun map(httpStatus: Int?, body: String?): ProviderError {
        val (code, message) = parse(body)
        val kind = when (code) {
            "1000", "1001", "1003" -> ErrorKind.INVALID_KEY
            "1005", "1220", "1311" -> ErrorKind.PERMISSION_DENIED
            "1113" -> ErrorKind.INSUFFICIENT_BALANCE // HTTP 429, but retrying cannot help
            "1302" -> ErrorKind.RATE_LIMITED
            "1305" -> ErrorKind.OVERLOADED
            "1308", "1310", "1313", "1316", "1317", "1318", "1319", "1320", "1321" -> ErrorKind.QUOTA_EXCEEDED
            "1211" -> ErrorKind.MODEL_NOT_FOUND
            "1301" -> ErrorKind.CONTENT_FILTERED
            "1261" -> ErrorKind.CONTEXT_TOO_LONG
            "1210", "1212", "1213", "1214", "1215", "1221", "1222" -> ErrorKind.BAD_REQUEST
            "1200", "1230", "1234" -> ErrorKind.SERVER_ERROR
            else -> byHttp(httpStatus)
        }
        val hint = when (kind) {
            ErrorKind.INVALID_KEY -> KEY_HINT
            ErrorKind.INSUFFICIENT_BALANCE -> "Rechargez le compte Z.ai ou choisissez un modèle gratuit."
            ErrorKind.MODEL_NOT_FOUND -> "Vérifiez l'identifiant du modèle (par défaut : ${ZaiProvider.DEFAULT_MODEL})."
            else -> null
        }
        return ProviderError(kind, httpStatus, code, message?.let { Redactor.redact(it) }, hint)
    }

    private fun byHttp(status: Int?): ErrorKind = when (status) {
        null -> ErrorKind.UNKNOWN
        401 -> ErrorKind.INVALID_KEY
        403 -> ErrorKind.PERMISSION_DENIED
        400, 404 -> ErrorKind.BAD_REQUEST
        408 -> ErrorKind.TIMEOUT
        429 -> ErrorKind.RATE_LIMITED
        in 500..599 -> ErrorKind.SERVER_ERROR
        else -> ErrorKind.UNKNOWN
    }

    /** Handles both `{"error":{"code","message"}}` and the gateway's `{"code","msg"}` shapes. */
    private fun parse(body: String?): Pair<String?, String?> {
        if (body.isNullOrBlank()) return null to null
        return try {
            val o = JSONObject(body)
            val err = o.optJSONObject("error")
            if (err != null) {
                err.opt("code")?.toString()?.takeIf { it != "null" } to err.strOrNull("message")
            } else {
                o.opt("code")?.toString()?.takeIf { it != "null" } to (o.strOrNull("msg") ?: o.strOrNull("message"))
            }
        } catch (e: Exception) {
            null to null
        }
    }
}
