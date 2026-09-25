package com.pocketide.rooms

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** What a server on this phone answered to a GET: the status, the start of the body, and the status line and headers. */
internal data class HttpAnswer(val status: Int, val body: String, val head: String = "")

/** Talks to servers inside Linux over 127.0.0.1, which proot shares with Android. */
internal object Loopback {
    private val LOCALHOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** A port nothing listens on right now, chosen by the system. */
    fun freePort(): Int = ServerSocket(0, 1, LOCALHOST).use { it.localPort }

    /** True when something accepts connections on [port]. */
    fun isListening(port: Int, timeoutMs: Int = 1_000): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOCALHOST, port), timeoutMs) }
        true
    } catch (closed: IOException) {
        false
    }

    /**
     * One plain HTTP/1.1 GET to 127.0.0.1:[port] with Host `localhost:<port>` (Antigravity's hub
     * refuses other host names), plus [headers]. Null when nothing answers like an HTTP server.
     * Blocking.
     */
    fun get(
        port: Int,
        path: String,
        timeoutMs: Int = 3_000,
        maxBody: Int = 4_096,
        headers: Map<String, String> = emptyMap(),
    ): HttpAnswer? = try {
        require(headers.all { (name, value) -> name.none(::breaksLine) && value.none(::breaksLine) }) { "A header may not break the line." }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOCALHOST, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val extra = headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
            val request = "GET $path HTTP/1.1\r\nHost: localhost:$port\r\nAccept: */*\r\n${extra}Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            parse(readUpTo(socket, maxBody + HEADER_ROOM))
        }
    } catch (failed: IOException) {
        null
    }

    private fun readUpTo(socket: Socket, limit: Int): String {
        val input = socket.getInputStream()
        val buffer = ByteArray(limit)
        var size = 0
        while (size < limit) {
            val read = try {
                input.read(buffer, size, limit - size)
            } catch (slow: java.net.SocketTimeoutException) {
                break
            }
            if (read < 0) break
            size += read
        }
        return String(buffer, 0, size, Charsets.UTF_8)
    }

    internal fun parse(raw: String): HttpAnswer? {
        val statusLine = raw.substringBefore("\r\n")
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) return null
        val status = parts[1].toIntOrNull() ?: return null
        return HttpAnswer(status, raw.substringAfter("\r\n\r\n", ""), raw.substringBefore("\r\n\r\n"))
    }

    private fun breaksLine(c: Char) = c == '\r' || c == '\n'

    private const val HEADER_ROOM = 8_192
}
