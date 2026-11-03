package com.aura.ai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloud LLM backend using OpenRouter (https://openrouter.ai). Opt-in only:
 * prompts leave the device. Embeddings stay local (MiniLM) either way.
 */
@Singleton
class OpenRouterLLM @Inject constructor(
    private val settings: LLMSettings,
    private val embeddingEngine: EmbeddingEngine,
) : LLMEngine {

    override val isReady: Boolean get() = settings.hasApiKey

    override suspend fun load(modelPath: String) = Unit

    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = flow {
        val key = checkNotNull(settings.apiKey) { "Clé API OpenRouter manquante" }
        val body = OpenRouterProtocol.buildRequestJson(
            settings.model, OpenRouterProtocol.toChatMessages(prompt), maxTokens,
        )
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
                throw IOException(OpenRouterProtocol.describeHttpError(code, err))
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    when (val ev = OpenRouterProtocol.parseSseLine(line)) {
                        is SseEvent.Delta -> emit(LLMToken(ev.text, false))
                        SseEvent.Done -> break
                        SseEvent.Ignore -> Unit
                    }
                }
            }
            emit(LLMToken("", true))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateFull(prompt: String, maxTokens: Int): String =
        generate(prompt, maxTokens).toList().joinToString("") { it.text }

    override suspend fun embed(text: String): FloatArray = embeddingEngine.embed(text)

    override fun release() = Unit
}
