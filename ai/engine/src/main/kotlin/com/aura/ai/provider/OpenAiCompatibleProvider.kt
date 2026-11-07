package com.aura.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Shared HTTPS client for providers that speak the OpenAI `/chat/completions` dialect (Z.ai,
 * OpenRouter, and later most others). Subclasses only describe their specifics.
 *
 * Safety: HTTPS only ([EndpointPolicy]); redirects are never followed (so the key cannot be
 * forwarded to another host); the key is only ever sent in the `Authorization` header; error bodies
 * are redacted before they can reach a message.
 */
abstract class OpenAiCompatibleProvider(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
    private val allowInsecureLoopback: Boolean = false,
) : AIProvider {

    protected abstract fun buildBody(request: ChatRequest, stream: Boolean): JSONObject
    protected abstract fun mapError(httpStatus: Int?, body: String?): ProviderError
    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    // ── streaming ────────────────────────────────────────────────────────────

    override fun streamChat(config: ProviderConfig, request: ChatRequest): Flow<ChatEvent> = flow {
        val conn = connect(config, request, stream = true)
        // Cancelling the collector must unblock a read stuck on the socket.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            val code = io(config) { conn.responseCode }
            if (code !in 200..299) {
                throw ProviderException(withRetryAfter(mapError(code, readError(conn, config)), conn))
            }
            val parser = OpenAiStreamParser(::mapError)
            val reader = io(config) { conn.inputStream.bufferedReader(Charsets.UTF_8) }
            reader.use { r ->
                while (true) {
                    val line = io(config) { r.readLine() } ?: break
                    for (event in parser.feed(line)) emit(event)
                    if (parser.done) break
                }
            }
            emit(parser.finish())
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ── non-streaming ────────────────────────────────────────────────────────

    override suspend fun complete(config: ProviderConfig, request: ChatRequest): ChatResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val conn = connect(config, request, stream = false)
            try {
                val code = io(config) { conn.responseCode }
                if (code !in 200..299) {
                    throw ProviderException(withRetryAfter(mapError(code, readError(conn, config)), conn))
                }
                val body = io(config) { conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText) }
                val obj = try { JSONObject(body) } catch (e: Exception) {
                    throw ProviderException(ProviderError(ErrorKind.UNKNOWN, code, detail = "réponse illisible"))
                }
                if (obj.has("error") && !obj.isNull("error")) throw ProviderException(mapError(code, body))
                val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw ProviderException(ProviderError(ErrorKind.UNKNOWN, code, detail = "réponse sans choix"))
                val message = choice.optJSONObject("message")
                val usage = obj.optJSONObject("usage")?.let {
                    val p = it.optInt("prompt_tokens", 0)
                    val c = it.optInt("completion_tokens", 0)
                    TokenUsage(p, c, it.optInt("total_tokens", p + c))
                }
                ChatResult(
                    text = message?.strOrNull("content").orEmpty(),
                    finishReason = FinishReason.fromWire(choice.strOrNull("finish_reason")),
                    usage = usage,
                    model = obj.strOrNull("model"),
                    latencyMs = (System.nanoTime() - started) / 1_000_000,
                )
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun testConnection(config: ProviderConfig): ConnectionResult =
        try {
            val r = complete(
                config,
                ChatRequest(
                    model = config.model,
                    messages = listOf(ChatMessage(ChatRole.USER, "Reply with the single word: OK")),
                    maxTokens = 32,
                    thinking = false,
                ),
            )
            ConnectionResult.Success(r.model ?: config.model, r.latencyMs, r.usage, r.text.trim().take(80))
        } catch (e: ProviderException) {
            ConnectionResult.Failure(e.error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionResult.Failure(ProviderError(ErrorKind.UNKNOWN, detail = Redactor.redact(e.javaClass.simpleName, listOf(config.apiKey))))
        }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private fun endpoint(config: ProviderConfig): URL =
        URL(EndpointPolicy.validate(config.baseUrl, allowInsecureLoopback) + "/chat/completions")

    private suspend fun connect(config: ProviderConfig, request: ChatRequest, stream: Boolean): HttpURLConnection {
        if (config.apiKey.isBlank()) throw ProviderException(ProviderError(ErrorKind.INVALID_KEY, detail = "aucune clé enregistrée"))
        val url = endpoint(config)
        val body = buildBody(request, stream).toString().toByteArray(Charsets.UTF_8)
        return io(config) {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
                extraHeaders().forEach { (k, v) -> setRequestProperty(k, v) }
                outputStream.use { it.write(body) }
            }
        }
    }

    /** Runs blocking I/O, turning low-level failures into classified [ProviderException]s. */
    private suspend inline fun <T> io(config: ProviderConfig, block: () -> T): T =
        try {
            block()
        } catch (e: ProviderException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a cancellation closes the socket: not a provider error
            throw ProviderException(classify(e, config), e)
        }

    private fun classify(e: IOException, config: ProviderConfig): ProviderError = when (e) {
        is SocketTimeoutException -> ProviderError(ErrorKind.TIMEOUT)
        is UnknownHostException, is NoRouteToHostException -> ProviderError(ErrorKind.NETWORK, detail = "hôte introuvable")
        is SSLException -> ProviderError(ErrorKind.NETWORK, detail = "échec TLS (certificat ou connexion sécurisée)")
        else -> ProviderError(ErrorKind.NETWORK, detail = Redactor.redact(e.javaClass.simpleName, listOf(config.apiKey)))
    }

    private fun readError(conn: HttpURLConnection, config: ProviderConfig): String =
        try {
            val raw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val buf = CharArray(MAX_ERROR_BODY)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }.orEmpty()
            Redactor.redact(raw, listOf(config.apiKey))
        } catch (e: IOException) {
            ""
        }

    private fun withRetryAfter(error: ProviderError, conn: HttpURLConnection): ProviderError {
        val seconds = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: return error
        return error.copy(retryAfterMs = (seconds * 1000).coerceIn(0, 60_000))
    }

    protected fun messagesJson(request: ChatRequest): JSONArray = JSONArray().also { arr ->
        request.messages.forEach { arr.put(JSONObject().put("role", it.role.wire).put("content", it.content)) }
    }

    private companion object { const val MAX_ERROR_BODY = 16 * 1024 }
}
