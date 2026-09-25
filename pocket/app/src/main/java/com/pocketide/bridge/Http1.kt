package com.pocketide.bridge

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/*
 * Just enough HTTP/1.1 (RFC 9112) for a reverse proxy that re-frames nothing: heads are parsed
 * strictly and written back out, bodies are copied as they came while their framing is
 * checked, so a request can never carry a second one past the bridge.
 */

/** A message the bridge will not pass on; [status] is what the client is told. */
internal class HttpProtocolException(val status: Int, message: String) : IOException(message)

/** Header fields in their original order, looked up without regard to case. */
internal class HeaderList(fields: List<Pair<String, String>> = emptyList()) {
    private val fields = fields.toMutableList()

    val size: Int get() = fields.size

    fun all(name: String): List<String> =
        fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun first(name: String): String? = fields.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun has(name: String): Boolean = fields.any { it.first.equals(name, ignoreCase = true) }

    fun remove(name: String) {
        fields.removeAll { it.first.equals(name, ignoreCase = true) }
    }

    fun removeIf(predicate: (name: String, value: String) -> Boolean) {
        fields.removeAll { predicate(it.first, it.second) }
    }

    fun add(name: String, value: String) {
        fields += name to value
    }

    /** Replaces every field called [name] with one, keeping the position of the first. */
    fun set(name: String, value: String) {
        val at = fields.indexOfFirst { it.first.equals(name, ignoreCase = true) }
        remove(name)
        if (at < 0) fields += name to value else fields.add(at, name to value)
    }

    /** Rewrites the values of [name] in place; a null result drops that field. */
    fun rewrite(name: String, transform: (String) -> String?) {
        val iterator = fields.listIterator()
        while (iterator.hasNext()) {
            val (field, value) = iterator.next()
            if (!field.equals(name, ignoreCase = true)) continue
            val next = transform(value)
            if (next == null) iterator.remove() else iterator.set(field to next)
        }
    }

    /** The comma-separated tokens of every field called [name], lower-cased, in order. */
    fun tokens(name: String): List<String> =
        all(name).flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun copy(): HeaderList = HeaderList(fields)

    fun writeTo(out: StringBuilder) {
        for ((name, value) in fields) out.append(name).append(": ").append(value).append("\r\n")
    }
}

internal class RequestHead(val method: String, val target: String, val headers: HeaderList) {
    val path: String get() = target.substringBefore('?')
    val query: String get() = target.substringAfter('?', "")

    fun encode(): ByteArray {
        val out = StringBuilder(256).append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        headers.writeTo(out)
        return out.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)
    }
}

internal class ResponseHead(val statusLine: String, val status: Int, val headers: HeaderList) {
    fun encode(): ByteArray {
        val out = StringBuilder(256).append(statusLine).append("\r\n")
        headers.writeTo(out)
        return out.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)
    }
}

/** How a message body is delimited (RFC 9112 §6). */
internal sealed interface Framing {
    data object None : Framing
    data class Fixed(val length: Long) : Framing
    data object Chunked : Framing
    /** Delimited by the sender closing the connection; responses only. */
    data object ToEnd : Framing
}

private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"
private const val MAX_METHOD_LENGTH = 32
private const val MAX_LENGTH_DIGITS = 18
private const val MAX_CHUNK_SIZE_DIGITS = 15
private const val MAX_TRAILER_FIELDS = 100

internal fun isToken(text: String): Boolean =
    text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }

/** Field values may hold visible characters, spaces, tabs and obs-text, never other controls. */
internal fun isFieldValue(text: String): Boolean =
    text.all { it == '\t' || it in ' '..'~' || it in '\u0080'..'ÿ' }

internal fun parseRequestHead(head: ByteArray, maxFields: Int): RequestHead {
    val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size != 3) throw HttpProtocolException(400, "Malformed request line.")
    val (method, target, version) = parts
    if (!isToken(method) || method.length > MAX_METHOD_LENGTH) throw HttpProtocolException(400, "Malformed method.")
    // Only origin-form: this is an origin server as far as the browser knows, never a proxy.
    if (!target.startsWith("/") || !target.all { it in '!'..'~' }) throw HttpProtocolException(400, "Malformed target.")
    if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpProtocolException(400, "Unsupported HTTP version.")
    return RequestHead(method, target, parseFields(lines.drop(1), maxFields, malformed = 400, tooMany = 431))
}

internal fun parseResponseHead(head: ByteArray, maxFields: Int): ResponseHead {
    val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
    val statusLine = lines.first()
    val parts = statusLine.split(' ', limit = 3)
    val status = parts.getOrNull(1)?.takeIf { it.length == 3 && it.all(Char::isDigit) }?.toInt()
    if (!parts[0].startsWith("HTTP/1.") || status == null || status < 100 || !isFieldValue(statusLine)) {
        throw HttpProtocolException(502, "Malformed status line.")
    }
    return ResponseHead(statusLine, status, parseFields(lines.drop(1), maxFields, malformed = 502, tooMany = 502))
}

private fun parseFields(lines: List<String>, maxFields: Int, malformed: Int, tooMany: Int): HeaderList {
    if (lines.size > maxFields) throw HttpProtocolException(tooMany, "Too many header fields.")
    val fields = ArrayList<Pair<String, String>>(lines.size)
    for (line in lines) {
        // Obsolete line folding and "Name : value" are refused rather than guessed at (§5.1, §5.2).
        val colon = line.indexOf(':')
        if (colon <= 0) throw HttpProtocolException(malformed, "Malformed header field.")
        val name = line.substring(0, colon)
        val value = line.substring(colon + 1).trim(' ', '\t')
        if (!isToken(name) || !isFieldValue(value)) throw HttpProtocolException(malformed, "Malformed header field.")
        fields += name to value
    }
    return HeaderList(fields)
}

/** The single Content-Length the fields agree on, or null when absent or contradictory. */
internal fun contentLength(headers: HeaderList): Long? {
    val values = headers.all("Content-Length").flatMap { it.split(',') }.map { it.trim() }
    if (values.isEmpty()) return null
    val first = values.first()
    if (values.any { it != first } || first.isEmpty() || first.length > MAX_LENGTH_DIGITS || !first.all(Char::isDigit)) return null
    return first.toLong()
}

/** Framing of a request body. Both lengths at once, or an unknown coding, is refused (smuggling). */
internal fun requestFraming(headers: HeaderList): Framing {
    val codings = headers.tokens("Transfer-Encoding")
    if (headers.has("Transfer-Encoding")) {
        if (headers.has("Content-Length")) throw HttpProtocolException(400, "Both Content-Length and Transfer-Encoding.")
        if (codings != listOf("chunked")) throw HttpProtocolException(400, "Unsupported transfer coding.")
        return Framing.Chunked
    }
    if (!headers.has("Content-Length")) return Framing.None
    val length = contentLength(headers) ?: throw HttpProtocolException(400, "Malformed Content-Length.")
    return if (length == 0L) Framing.None else Framing.Fixed(length)
}

/** Framing of a response body (RFC 9112 §6.3). */
internal fun responseFraming(requestMethod: String, response: ResponseHead): Framing {
    val status = response.status
    if (requestMethod == "HEAD" || status in 100..199 || status == 204 || status == 304) return Framing.None
    if (response.headers.has("Transfer-Encoding")) {
        return if (response.headers.tokens("Transfer-Encoding").lastOrNull() == "chunked") Framing.Chunked else Framing.ToEnd
    }
    if (!response.headers.has("Content-Length")) return Framing.ToEnd
    val length = contentLength(response.headers) ?: throw HttpProtocolException(502, "Malformed Content-Length.")
    return Framing.Fixed(length)
}

/**
 * A buffered input stream that can also read an HTTP head or a CRLF line with a size limit.
 * Whatever was read past the head stays buffered for the body, or for a WebSocket's frames.
 */
internal class HttpInput(private val source: InputStream, capacity: Int) : InputStream() {
    private val buffer = ByteArray(capacity)
    private var start = 0
    private var end = 0

    /**
     * Returns the head without its closing blank line, or null when the stream ended before a
     * single byte arrived. [beforeRead] runs before every blocking read (timeouts are set there).
     */
    fun readHead(limit: Int, beforeRead: () -> Unit = {}): ByteArray? {
        require(limit <= buffer.size) { "Head limit larger than the buffer." }
        var scanned = 0
        while (true) {
            val blank = indexOf(BLANK_LINE, from = start + max(0, scanned - BLANK_LINE.size + 1))
            if (blank >= 0) {
                val head = buffer.copyOfRange(start, blank)
                start = blank + BLANK_LINE.size
                return head
            }
            scanned = end - start
            if (scanned >= limit) throw HttpProtocolException(431, "The head is too large.")
            compact()
            beforeRead()
            val read = source.read(buffer, end, limit - scanned)
            if (read < 0) {
                if (end == start) return null
                throw HttpProtocolException(400, "The head ended early.")
            }
            end += read
        }
    }

    /** One line without its CRLF (ISO-8859-1). A bare LF, an early end or a long line fails. */
    fun readLine(limit: Int): String {
        var scanned = 0
        while (true) {
            val newline = indexOf(LF, from = start + scanned)
            if (newline >= 0) {
                if (newline == start || buffer[newline - 1] != CR) throw HttpProtocolException(400, "A line did not end with CRLF.")
                val line = String(buffer, start, newline - 1 - start, Charsets.ISO_8859_1)
                start = newline + 1
                return line
            }
            scanned = end - start
            if (scanned >= min(limit, buffer.size)) throw HttpProtocolException(400, "A line is too long.")
            compact()
            val read = source.read(buffer, end, min(buffer.size - end, limit - scanned))
            if (read < 0) throw EOFException("The stream ended inside a line.")
            end += read
        }
    }

    override fun read(): Int {
        if (start == end && fill() < 0) return -1
        return buffer[start++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (start == end) {
            // Large reads skip the buffer; small ones refill it.
            if (len >= buffer.size) return source.read(b, off, len)
            if (fill() < 0) return -1
        }
        val count = min(len, end - start)
        System.arraycopy(buffer, start, b, off, count)
        start += count
        return count
    }

    override fun available(): Int = end - start

    private fun fill(): Int {
        start = 0
        end = 0
        val read = source.read(buffer, 0, buffer.size)
        if (read > 0) end = read
        return read
    }

    private fun compact() {
        if (start == 0) return
        System.arraycopy(buffer, start, buffer, 0, end - start)
        end -= start
        start = 0
    }

    private fun indexOf(pattern: ByteArray, from: Int): Int {
        var i = from
        while (i <= end - pattern.size) {
            if (pattern.indices.all { buffer[i + it] == pattern[it] }) return i
            i++
        }
        return -1
    }

    private companion object {
        const val CR = '\r'.code.toByte()
        val LF = byteArrayOf('\n'.code.toByte())
        val BLANK_LINE = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

/** Copies one body as framed, calling [onProgress] after every write. */
internal fun copyBody(input: HttpInput, output: OutputStream, framing: Framing, buffer: ByteArray, onProgress: () -> Unit) {
    when (framing) {
        Framing.None -> Unit
        is Framing.Fixed -> copyFixed(input, output, framing.length, buffer, onProgress)
        Framing.Chunked -> copyChunked(input, output, buffer, onProgress)
        Framing.ToEnd -> copyToEnd(input, output, buffer, onProgress)
    }
    output.flush()
}

internal fun copyToEnd(input: InputStream, output: OutputStream, buffer: ByteArray, onProgress: () -> Unit) {
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        output.write(buffer, 0, read)
        onProgress()
    }
}

private fun copyFixed(input: InputStream, output: OutputStream, length: Long, buffer: ByteArray, onProgress: () -> Unit) {
    var left = length
    while (left > 0) {
        val read = input.read(buffer, 0, min(buffer.size.toLong(), left).toInt())
        if (read < 0) throw EOFException("The body ended early.")
        output.write(buffer, 0, read)
        left -= read
        onProgress()
    }
}

/** Chunk lines and trailers are checked and passed on as they are; the data is copied. */
private fun copyChunked(input: HttpInput, output: OutputStream, buffer: ByteArray, onProgress: () -> Unit) {
    while (true) {
        val sizeLine = input.readLine(MAX_CHUNK_LINE)
        val size = chunkSize(sizeLine)
        output.write("$sizeLine\r\n".toByteArray(Charsets.ISO_8859_1))
        if (size == 0L) break
        copyFixed(input, output, size, buffer, onProgress)
        if (input.readLine(MAX_CHUNK_LINE).isNotEmpty()) throw HttpProtocolException(400, "Chunk data was longer than its size.")
        output.write(CRLF)
        onProgress()
    }
    repeat(MAX_TRAILER_FIELDS + 1) {
        val trailer = input.readLine(MAX_CHUNK_LINE)
        output.write("$trailer\r\n".toByteArray(Charsets.ISO_8859_1))
        if (trailer.isEmpty()) {
            onProgress()
            return
        }
    }
    throw HttpProtocolException(400, "Too many trailer fields.")
}

private fun chunkSize(line: String): Long {
    val digits = line.substringBefore(';').trimEnd(' ', '\t')
    val valid = digits.isNotEmpty() && digits.length <= MAX_CHUNK_SIZE_DIGITS &&
        digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && isFieldValue(line)
    if (!valid) throw HttpProtocolException(400, "Malformed chunk size.")
    return digits.toLong(16)
}

private const val MAX_CHUNK_LINE = 8 * 1024
private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
