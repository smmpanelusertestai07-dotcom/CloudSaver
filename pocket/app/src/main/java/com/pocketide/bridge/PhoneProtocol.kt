package com.pocketide.bridge

import com.pocketide.core.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal typealias PhoneHandler = suspend (agentId: String, args: JsonObject) -> JsonElement

/** The ops every room may call. Registering an op again replaces its handler. */
internal class PhoneOps {
    private val handlers = ConcurrentHashMap<String, PhoneHandler>()

    fun register(op: String, handler: PhoneHandler) {
        require(OP_NAME.matches(op)) { "\"$op\" is not a valid op name." }
        handlers[op] = handler
    }

    fun find(op: String): PhoneHandler? = handlers[op]

    fun names(): List<String> = handlers.keys.sorted()

    private companion object {
        val OP_NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

internal data class PhoneLimits(
    val maxLineBytes: Int = 1024 * 1024,
    val maxInFlight: Int = 16,
    val requestTimeout: Duration = 10.minutes,
    val maxConnectionsPerRoom: Int = 8,
)

/**
 * One connection from a room: JSON lines in, JSON lines out. Each request runs in its own
 * coroutine, so a slow MCP tool does not hold up a quick one, and a failing request is answered
 * with an error while the connection carries on. Nothing here knows about sockets: the Android
 * side hands over the two streams.
 */
internal class PhoneSession(
    private val agentId: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val ops: PhoneOps,
    private val limits: PhoneLimits = PhoneLimits(),
) {
    private val writeLock = Mutex()
    private val inFlight = AtomicInteger()

    private class Request(val id: JsonElement, val op: String, val args: JsonObject)

    private class BadRequest(val id: JsonElement, message: String) : Exception(message)

    /**
     * Serves requests until the client closes its sending side, then waits for the replies still
     * owed. A reply that cannot be written means the client is gone: its requests are cancelled.
     * Reads block, so callers run this on an IO dispatcher.
     */
    suspend fun serve() = coroutineScope {
        val requestJob = SupervisorJob(coroutineContext.job)
        val requests = CoroutineScope(coroutineContext + requestJob)
        val lines = LineReader(input, limits.maxLineBytes)
        try {
            while (true) {
                when (val line = lines.next()) {
                    LineReader.Line.End -> break
                    LineReader.Line.TooLong -> {
                        send(failure(JsonNull, "The request is longer than ${describeSize(limits.maxLineBytes)}."))
                        break
                    }
                    is LineReader.Line.Text -> if (line.text.isNotBlank()) accept(line.text, requests, requestJob)
                }
            }
        } catch (e: IOException) {
            requestJob.cancel()
        }
        requestJob.complete()
        requestJob.join()
    }

    private suspend fun accept(text: String, requests: CoroutineScope, requestJob: Job) {
        val request = try {
            parse(text)
        } catch (e: BadRequest) {
            return send(failure(e.id, e.message.orEmpty()))
        }
        val handler = ops.find(request.op)
            ?: return send(failure(request.id, "Unknown op \"${request.op}\". Known ops: ${ops.names().joinToString()}."))
        if (inFlight.incrementAndGet() > limits.maxInFlight) {
            inFlight.decrementAndGet()
            return send(failure(request.id, "Too many requests at once on this connection. Wait for some to finish."))
        }
        requests.launch {
            try {
                send(run(request, handler))
            } catch (e: IOException) {
                requestJob.cancel()
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private suspend fun run(request: Request, handler: PhoneHandler): JsonObject =
        try {
            success(request.id, withTimeout(limits.requestTimeout) { handler(agentId, request.args) })
        } catch (e: TimeoutCancellationException) {
            failure(request.id, "\"${request.op}\" took too long and was stopped.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            failure(request.id, plain(e) ?: "\"${request.op}\" was given something it cannot use.")
        } catch (e: IllegalStateException) {
            failure(request.id, plain(e) ?: "\"${request.op}\" cannot run right now.")
        } catch (e: Exception) {
            failure(request.id, "\"${request.op}\" failed on the phone.")
        }

    private fun parse(text: String): Request {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw BadRequest(JsonNull, "The request is not valid JSON.")
        }
        val fields = element as? JsonObject ?: throw BadRequest(JsonNull, "The request must be a JSON object.")
        val id = fields["id"] ?: JsonNull
        if (id !is JsonPrimitive) throw BadRequest(JsonNull, "\"id\" must be a number or a string.")
        val op = (fields["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BadRequest(id, "The request has no \"op\".")
        val args = when (val raw = fields["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw BadRequest(id, "\"args\" must be a JSON object.")
        }
        return Request(id, op, args)
    }

    /** One reply, one line. A failed write closes the input as well, so the read loop ends. */
    private suspend fun send(reply: JsonObject) {
        val bytes = (reply.toString() + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            try {
                output.write(bytes)
                output.flush()
            } catch (e: IOException) {
                closeQuietly(input)
                throw e
            }
        }
    }

    /** A handler's own sentence, with anything that looks like a secret removed (a backstop). */
    private fun plain(error: Exception): String? = error.message?.takeIf { it.isNotBlank() }?.let(Redact::text)

    private fun success(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    }

    private fun failure(id: JsonElement, message: String) = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", message)
    }

    private companion object {
        const val MIB = 1024 * 1024

        fun describeSize(bytes: Int): String =
            if (bytes >= MIB && bytes % MIB == 0) "${bytes / MIB} MB" else "$bytes bytes"
    }
}

/** Reads newline-terminated lines, refusing any longer than [maxBytes] without buffering them. */
internal class LineReader(private val input: InputStream, private val maxBytes: Int) {
    private val buffer = ByteArray(BUFFER)
    private var start = 0
    private var end = 0

    sealed interface Line {
        data class Text(val text: String) : Line
        data object TooLong : Line
        data object End : Line
    }

    fun next(): Line {
        val line = ByteArrayOutputStream()
        while (true) {
            if (start == end) {
                val read = input.read(buffer)
                if (read < 0) return if (line.size() == 0) Line.End else Line.Text(decode(line))
                start = 0
                end = read
            }
            var newline = start
            while (newline < end && buffer[newline] != LF) newline++
            if (line.size() + (newline - start) > maxBytes) return Line.TooLong
            line.write(buffer, start, newline - start)
            if (newline < end) {
                start = newline + 1
                return Line.Text(decode(line))
            }
            start = end
        }
    }

    private fun decode(line: ByteArrayOutputStream) = line.toString(Charsets.UTF_8.name()).removeSuffix("\r")

    private companion object {
        const val BUFFER = 8192
        const val LF = '\n'.code.toByte()
    }
}
