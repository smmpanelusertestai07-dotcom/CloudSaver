package com.pocketide.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One request as a server behind the bridge received it. */
class Recorded(val requestLine: String, val headers: List<Pair<String, String>>, val body: ByteArray, val chunked: Boolean) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val bodyText: String get() = String(body, Charsets.UTF_8)
}

/** What a test server does with one request: write a reply to [output], read more from [input]. */
class Exchange(val request: Recorded, val input: InputStream, val output: OutputStream, val socket: Socket) {
    fun reply(status: Int, body: String, vararg headers: Pair<String, String>) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 $status X\r\n")
        headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
        head.append("Content-Length: ${bytes.size}\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1) + bytes)
        output.flush()
    }
}

/**
 * A small HTTP/1.1 server on 127.0.0.1 standing in for a server inside Linux. It keeps reading
 * requests on a connection until the connection ends, whatever Connection says, so a request
 * smuggled behind another would show up in [requests].
 */
class TestUpstream(private val handle: (Exchange) -> Unit = { it.reply(200, "ok") }) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    val requests = LinkedBlockingQueue<Recorded>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "test-upstream") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += socket
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    fun take(): Recorded = requests.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("The server behind the bridge got no request.")

    fun nothingArrives(millis: Long = 300): Boolean = requests.poll(millis, TimeUnit.MILLISECONDS) == null

    override fun close() {
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    requests += request
                    handle(Exchange(request, input, output, socket))
                    if (request.header("Upgrade") != null) return
                }
            }
        } catch (e: IOException) {
            // The bridge, or the end of the test, closed the connection.
        }
    }

    private fun readRequest(input: InputStream): Recorded? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val headers = lines.drop(1).map { it.substringBefore(':') to it.substringAfter(':').trim() }
        val get = { name: String -> headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }
        val chunked = get("Transfer-Encoding")?.equals("chunked", ignoreCase = true) == true
        val body = when {
            chunked -> readChunked(input)
            get("Content-Length") != null -> readExactly(input, get("Content-Length")!!.toInt())
            else -> ByteArray(0)
        }
        return Recorded(lines.first(), headers, body, chunked)
    }
}

/** Reads an HTTP head (without its blank line) from [input]; null at a clean end of stream. */
fun readHead(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) {
            if (bytes.size() == 0) return null
            throw EOFException("The head ended early.")
        }
        bytes.write(b)
        val text = bytes.toByteArray()
        if (text.size >= 4 && String(text, text.size - 4, 4, Charsets.ISO_8859_1) == "\r\n\r\n") {
            return String(text, 0, text.size - 4, Charsets.ISO_8859_1)
        }
    }
}

fun readExactly(input: InputStream, count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(bytes, read, count - read)
        if (n < 0) throw EOFException("The body ended early.")
        read += n
    }
    return bytes
}

fun readLineCrlf(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) throw EOFException("The line ended early.")
        if (b == '\n'.code) return String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
        bytes.write(b)
    }
}

fun readChunked(input: InputStream): ByteArray {
    val body = ByteArrayOutputStream()
    while (true) {
        val size = readLineCrlf(input).substringBefore(';').trim().toInt(16)
        if (size == 0) break
        body.write(readExactly(input, size))
        check(readLineCrlf(input).isEmpty()) { "Chunk data was not followed by CRLF." }
    }
    while (readLineCrlf(input).isNotEmpty()) {
        // Trailer fields.
    }
    return body.toByteArray()
}

/** A response as the browser would receive it. */
class RawResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray, val raw: String) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val bodyText: String get() = String(body, Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): RawResponse {
            val raw = String(bytes, Charsets.ISO_8859_1)
            val end = raw.indexOf("\r\n\r\n")
            check(end >= 0) { "No complete response head in: $raw" }
            val lines = raw.substring(0, end).split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val headers = lines.drop(1).map { it.substringBefore(':') to it.substringAfter(':').trim() }
            return RawResponse(status, headers, bytes.copyOfRange(end + 4, bytes.size), raw)
        }
    }
}

/** Sends [request] as is and reads everything until the bridge closes the connection. */
fun rawExchange(port: Int, request: String, timeoutMs: Int = 5_000): RawResponse =
    rawExchangeBytes(port, request.toByteArray(Charsets.ISO_8859_1), timeoutMs)

fun rawExchangeBytes(port: Int, request: ByteArray, timeoutMs: Int = 5_000): RawResponse =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = timeoutMs
        socket.getOutputStream().apply {
            write(request)
            flush()
        }
        RawResponse.parse(readUntilClosed(socket.getInputStream()))
    }

/**
 * Everything until the peer closes. A peer that closes with bytes of ours still unread sends a
 * reset after its reply; what arrived before the reset is still the reply.
 */
fun readUntilClosed(input: InputStream): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    try {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            bytes.write(buffer, 0, read)
        }
    } catch (e: SocketException) {
        if (bytes.size() == 0) throw e
    }
    return bytes.toByteArray()
}

/** A request head with CRLFs, ready to send. */
fun head(requestLine: String, vararg headers: Pair<String, String>): String =
    buildString {
        append(requestLine).append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }

val BridgedPort.token: String get() = entryUrl.substringAfter("t=")

val BridgedPort.cookie: Pair<String, String> get() = "Cookie" to "pide_$bridgePort=$token"

val BridgedPort.host: Pair<String, String> get() = "Host" to "127.0.0.1:$bridgePort"

/** A port nothing listens on (bound, then released). */
fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
