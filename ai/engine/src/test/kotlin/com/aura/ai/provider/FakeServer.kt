package com.aura.ai.provider

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A throw-away loopback HTTP/1.1 server standing in for a provider API in unit tests.
 * Built on plain sockets: `com.sun.net.httpserver` is not on the Android unit-test compile classpath.
 */
class FakeServer : AutoCloseable {
    data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Exchange internal constructor(val request: Recorded, internal val out: OutputStream)

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)
    val requests = CopyOnWriteArrayList<Recorded>()

    @Volatile var handler: (Exchange) -> Unit = { ex -> respond(ex, 200, "{}") }

    val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}/api/paas/v4"

    init {
        Thread {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    Thread { handle(socket) }.apply { isDaemon = true; start() }
                } catch (e: IOException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length).also { buf ->
                    var read = 0
                    while (read < length) {
                        val n = input.read(buf, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                }.toString(Charsets.UTF_8)
                val recorded = Recorded(parts[0], parts.getOrElse(1) { "/" }.substringBefore('?'), headers, body)
                requests += recorded
                val out = s.getOutputStream()
                handler(Exchange(recorded, out))
                out.flush()
            } catch (e: Exception) {
                // client gave up (timeouts) or the handler threw: nothing to report in a fake
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    fun respond(ex: Exchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            .append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        ex.out.write(head.toString().toByteArray(Charsets.UTF_8))
        ex.out.write(bytes)
        ex.out.flush()
    }

    /** Streams [lines] as server-sent events (chunked), then ends the response cleanly. */
    fun sse(ex: Exchange, lines: List<String>) {
        ex.out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
        lines.forEach { line ->
            val data = (line + "\n\n").toByteArray(Charsets.UTF_8)
            ex.out.write("${Integer.toHexString(data.size)}\r\n".toByteArray())
            ex.out.write(data)
            ex.out.write("\r\n".toByteArray())
            ex.out.flush()
        }
        ex.out.write("0\r\n\r\n".toByteArray())
        ex.out.flush()
    }

    override fun close() {
        running.set(false)
        try { serverSocket.close() } catch (e: IOException) { }
    }
}
