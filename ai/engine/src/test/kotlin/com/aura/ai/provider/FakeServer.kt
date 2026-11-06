package com.aura.ai.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A throw-away loopback HTTP server standing in for a provider API in unit tests. */
class FakeServer : AutoCloseable {
    data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile var handler: (HttpExchange) -> Unit = { ex -> respond(ex, 200, "{}") }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/api/paas/v4"

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(
                ex.requestMethod, ex.requestURI.path,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }, body,
            )
            try { handler(ex) } finally { ex.close() }
        }
        server.start()
    }

    fun respond(ex: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    fun sse(ex: HttpExchange, lines: List<String>) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0) // chunked
        ex.responseBody.use { out ->
            lines.forEach { out.write((it + "\n\n").toByteArray()); out.flush() }
        }
    }

    override fun close() = server.stop(0)
}
