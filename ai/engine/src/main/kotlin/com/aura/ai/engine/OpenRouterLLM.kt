package com.aura.ai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud LLM backend using OpenRouter (https://openrouter.ai). Opt-in only:
 * prompts leave the device. Embeddings stay local (MiniLM) either way.
 *
 * Free models are often saturated (HTTP 429 / provider errors). When the user allows it,
 * a free model that fails BEFORE producing any token is replaced by the next curated free model.
 */
@Singleton
class OpenRouterLLM @Inject constructor(
    private val settings: LLMSettings,
    private val embeddingEngine: EmbeddingEngine,
) : LLMEngine {

    private class HttpFailure(val code: Int, message: String) : IOException(message)

    override val isReady: Boolean get() = settings.hasApiKey

    override suspend fun load(modelPath: String) = Unit

    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = flow {
        val key = checkNotNull(settings.apiKey) { "Clé API OpenRouter manquante" }
        val turns = OpenRouterProtocol.toChatMessages(prompt)
        val chain = if (settings.autoFallback) {
            OpenRouterProtocol.fallbackChain(settings.model, OpenRouterCatalog.FALLBACK.map { it.id })
        } else listOf(settings.model)

        val tried = ArrayList<String>()
        var last: HttpFailure? = null
        for ((i, model) in chain.withIndex()) {
            tried += model
            try {
                streamOnce(key, model, turns, maxTokens) { emit(LLMToken(it, false)) }
                settings.lastModelUsed = model
                emit(LLMToken("", true))
                return@flow
            } catch (e: HttpFailure) {
                last = e
                Timber.w("OpenRouter $model failed: HTTP ${e.code}")
                if (!OpenRouterProtocol.isRetryable(e.code) || i == chain.lastIndex) break
                delay(800)
            }
        }
        val failure = checkNotNull(last)
        val hint = if (failure.code == 429) {
            " Les modèles gratuits sont limités : réessayez dans une minute, ou ajoutez des crédits OpenRouter."
        } else ""
        val triedNote = if (tried.size > 1) " (modèles essayés : ${tried.joinToString()})" else ""
        throw IOException((failure.message ?: "Erreur OpenRouter") + triedNote + hint)
    }.flowOn(Dispatchers.IO)

    private suspend fun streamOnce(
        key: String,
        model: String,
        turns: List<ChatTurn>,
        maxTokens: Int,
        onToken: suspend (String) -> Unit,
    ) {
        val body = OpenRouterProtocol.buildRequestJson(model, turns, maxTokens)
        val conn = (URL(OpenRouterProtocol.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("HTTP-Referer", "https://github.com/cortexAI-2025/Aura")
            setRequestProperty("X-Title", "Aura")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpFailure(code, OpenRouterProtocol.describeHttpError(code, err))
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val ev = OpenRouterProtocol.parseSseLine(line)) {
                        is SseEvent.Delta -> onToken(ev.text)
                        SseEvent.Done -> break
                        SseEvent.Ignore -> Unit
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun generateFull(prompt: String, maxTokens: Int): String =
        generate(prompt, maxTokens).toList().joinToString("") { it.text }

    override suspend fun embed(text: String): FloatArray = embeddingEngine.embed(text)

    override fun release() = Unit
}
