package com.pocketide.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class PhoneSessionTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val ops = PhoneOps().apply {
        register("echo") { agent, args -> buildJsonObject { put("agent", agent); put("args", args) } }
    }
    private val toSession = Pipe.open()
    private val fromSession = Pipe.open()
    private val clientOut = Channels.newOutputStream(toSession.sink())
    private val clientIn = BufferedReader(InputStreamReader(Channels.newInputStream(fromSession.source()), Charsets.UTF_8))
    private val scope = CoroutineScope(Dispatchers.IO)

    @After fun tearDown() {
        listOf(toSession.sink(), toSession.source(), fromSession.sink(), fromSession.source()).forEach { it.close() }
        scope.cancel()
    }

    private fun start(limits: PhoneLimits = PhoneLimits()): Job = scope.launch {
        val input = Channels.newInputStream(toSession.source())
        val output = Channels.newOutputStream(fromSession.sink())
        PhoneSession("claude", input, output, ops, limits).serve()
    }

    private fun send(line: String) {
        clientOut.write((line + "\n").toByteArray(Charsets.UTF_8))
        clientOut.flush()
    }

    private fun reply(): JsonObject = Json.parseToJsonElement(clientIn.readLine() ?: error("The session closed.")).jsonObject

    private fun JsonObject.ok() = getValue("ok").jsonPrimitive.boolean

    private fun JsonObject.error() = getValue("error").jsonPrimitive.content

    @Test fun `a request reaches its handler with the room's agent id and the reply carries its id`() {
        start()
        send("""{"id":1,"op":"echo","args":{"x":1}}""")
        assertEquals(
            Json.parseToJsonElement("""{"id":1,"ok":true,"result":{"agent":"claude","args":{"x":1}}}"""),
            reply(),
        )
        send("""{"id":"abc","op":"echo"}""")
        val second = reply()
        assertEquals(JsonPrimitive("abc"), second["id"])
        assertEquals(JsonObject(emptyMap()), second.getValue("result").jsonObject.getValue("args"))
    }

    @Test fun `an unknown op is an error that lists the known ones`() {
        start()
        send("""{"id":2,"op":"nope","args":{}}""")
        val reply = reply()
        assertFalse(reply.ok())
        assertEquals(JsonPrimitive(2), reply["id"])
        assertEquals("Unknown op \"nope\". Known ops: echo.", reply.error())
    }

    @Test fun `a failing handler is answered and the connection carries on`() {
        ops.register("plain") { _, _ -> throw IllegalStateException("Sign in to GitHub first.") }
        ops.register("broken") { _, _ -> throw RuntimeException("internal detail that stays on the phone") }
        ops.register("leaky") { _, _ -> throw IllegalArgumentException("bad token=ghp_abcdefghijklmnopqrstuvwxyz0123") }
        start()

        send("""{"id":1,"op":"plain"}""")
        assertEquals("Sign in to GitHub first.", reply().error())
        send("""{"id":2,"op":"broken"}""")
        assertEquals("\"broken\" failed on the phone.", reply().error())
        send("""{"id":3,"op":"leaky"}""")
        val leaky = reply().error()
        assertFalse(leaky.contains("ghp_"))
        assertTrue(leaky.contains("[hidden]"))
        send("""{"id":4,"op":"echo"}""")
        assertTrue(reply().ok())
    }

    @Test fun `malformed requests are answered and are not fatal`() {
        start()
        val cases = listOf(
            "not json" to "The request is not valid JSON.",
            "[1,2]" to "The request must be a JSON object.",
            """{"id":{},"op":"echo"}""" to "\"id\" must be a number or a string.",
            """{"id":3}""" to "The request has no \"op\".",
            """{"id":4,"op":7}""" to "The request has no \"op\".",
            """{"id":5,"op":"echo","args":[1]}""" to "\"args\" must be a JSON object.",
        )
        for ((line, error) in cases) {
            send(line)
            val reply = reply()
            assertFalse(line, reply.ok())
            assertEquals(line, error, reply.error())
        }
        send("\r")
        send("""{"id":6,"op":"echo"}""")
        val last = reply()
        assertTrue(last.ok())
        assertEquals(JsonPrimitive(6), last["id"])
    }

    @Test fun `a line over 1 MB is refused and ends the connection`() {
        val session = start()
        val writer = thread(isDaemon = true) {
            runCatching {
                clientOut.write(ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() })
                clientOut.write('\n'.code)
                clientOut.flush()
            }
        }
        val reply = reply()
        assertFalse(reply.ok())
        assertEquals(JsonNull, reply["id"])
        assertEquals("The request is longer than 1 MB.", reply.error())
        runBlocking { withTimeout(5_000) { session.join() } }
        toSession.source().close()
        writer.join(5_000)
    }

    @Test fun `a line of exactly the limit is still read`() {
        start(PhoneLimits(maxLineBytes = 64))
        val prefix = """{"id":1,"op":"echo","args":{"p":""""
        val suffix = """"}}"""
        val line = prefix + "x".repeat(64 - prefix.length - suffix.length) + suffix
        assertEquals(64, line.length)
        send(line)
        assertTrue(reply().ok())
    }

    @Test fun `requests on one connection run concurrently`() {
        // "wait" can only finish once "release" has run, so both replies arriving proves it.
        val gate = CompletableDeferred<Unit>()
        ops.register("wait") { _, _ ->
            gate.await()
            JsonPrimitive("waited")
        }
        ops.register("release") { _, _ ->
            gate.complete(Unit)
            JsonPrimitive("released")
        }
        start()
        send("""{"id":1,"op":"wait"}""")
        send("""{"id":2,"op":"release"}""")
        val replies = List(2) { reply() }.associate { it.getValue("id").jsonPrimitive.content to it.getValue("result") }
        assertEquals(mapOf("1" to JsonPrimitive("waited"), "2" to JsonPrimitive("released")), replies)
    }

    @Test fun `many concurrent requests on one connection all get their own reply`() {
        ops.register("double") { _, args -> JsonPrimitive(args.getValue("n").jsonPrimitive.content.toInt() * 2) }
        start()
        repeat(12) { send("""{"id":$it,"op":"double","args":{"n":$it}}""") }
        val replies = List(12) { reply() }.associate { it.getValue("id").jsonPrimitive.content.toInt() to it.getValue("result").jsonPrimitive.content.toInt() }
        assertEquals((0 until 12).associateWith { it * 2 }, replies)
    }

    @Test fun `a request that runs too long is stopped`() {
        val cancelled = AtomicBoolean()
        ops.register("slow") { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        start(PhoneLimits(requestTimeout = 200.milliseconds))
        send("""{"id":9,"op":"slow"}""")
        val reply = reply()
        assertEquals("\"slow\" took too long and was stopped.", reply.error())
        assertTrue(cancelled.get())
    }

    @Test fun `too many requests at once are turned away until some finish`() {
        val gate = CompletableDeferred<Unit>()
        ops.register("wait") { _, _ ->
            gate.await()
            JsonPrimitive(true)
        }
        start(PhoneLimits(maxInFlight = 2))
        send("""{"id":1,"op":"wait"}""")
        send("""{"id":2,"op":"wait"}""")
        send("""{"id":3,"op":"wait"}""")
        val busy = reply()
        assertEquals(JsonPrimitive(3), busy["id"])
        assertTrue(busy.error().startsWith("Too many requests at once"))
        gate.complete(Unit)
        assertEquals(setOf(1, 2), List(2) { reply().getValue("id").jsonPrimitive.content.toInt() }.toSet())
        send("""{"id":4,"op":"wait"}""")
        assertTrue(reply().ok())
    }

    @Test fun `replies still owed are sent after the client stops sending`() {
        val gate = CompletableDeferred<Unit>()
        ops.register("wait") { _, _ ->
            gate.await()
            JsonPrimitive("late")
        }
        val session = start()
        send("""{"id":1,"op":"wait"}""")
        clientOut.close()
        Thread.sleep(100)
        assertFalse(session.isCompleted)
        gate.complete(Unit)
        assertEquals(JsonPrimitive("late"), reply()["result"])
        runBlocking { withTimeout(5_000) { session.join() } }
    }

    @Test fun `stopping the session cancels the requests it is running`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        ops.register("forever") { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val session = start()
        send("""{"id":1,"op":"forever"}""")
        runBlocking {
            withTimeout(5_000) { started.await() }
            // The owner of the socket closes it; the blocked read ends and the job is cancelled.
            toSession.source().close()
            session.cancelAndJoin()
            withTimeout(5_000) { cancelled.await() }
        }
    }

    @Test fun `registering an op again replaces it and op names are checked`() {
        ops.register("echo") { _, _ -> JsonPrimitive("replaced") }
        start()
        send("""{"id":1,"op":"echo"}""")
        assertEquals(JsonPrimitive("replaced"), reply()["result"])
        for (bad in listOf("", "Echo", "1op", "op-name", "a".repeat(65))) {
            assertThrows<IllegalArgumentException> { ops.register(bad) { _, _ -> JsonNull } }
        }
    }

    @Test fun `line reader splits on newlines, strips CR and reports long lines`() {
        val reader = LineReader("one\r\ntwo\n\nthree".byteInputStream(), 5)
        assertEquals(LineReader.Line.Text("one"), reader.next())
        assertEquals(LineReader.Line.Text("two"), reader.next())
        assertEquals(LineReader.Line.Text(""), reader.next())
        assertEquals(LineReader.Line.Text("three"), reader.next())
        assertEquals(LineReader.Line.End, reader.next())
        assertEquals(LineReader.Line.TooLong, LineReader("toolong\n".byteInputStream(), 5).next())
    }
}
