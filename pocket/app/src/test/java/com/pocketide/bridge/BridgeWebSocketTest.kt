package com.pocketide.bridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BridgeWebSocketTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val limits = BridgeLimits(headTimeoutMs = 1_000, idleTimeoutMs = 500, reaperIntervalMs = 100)
    private val bridge = LoopbackPortBridge(limits)
    private val upstreams = mutableListOf<TestUpstream>()
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @After fun tearDown() {
        bridge.shutdown()
        upstreams.forEach(TestUpstream::close)
        client.dispatcher.executorService.shutdown()
    }

    private fun upstream(handle: (Exchange) -> Unit) = TestUpstream(handle).also { upstreams += it }

    @Test fun `websocket frames pass both ways between a real client and server`() {
        val up = upstream(::echoWebSocket)
        val port = bridge.expose(up.port, "terminal", mapOf("X-Pocket-Secret" to "s3cret"))
        val events = LinkedBlockingQueue<Any>()
        val request = Request.Builder().url("ws://127.0.0.1:${port.bridgePort}/term?x=1")
            .header("Cookie", "pide_${port.bridgePort}=${port.token}")
            .header("Origin", port.origin)
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events += "open ${response.code}"
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events += text
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events += bytes
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events += "closing $code $reason"
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events += "failure $t"
            }
        })
        fun next() = events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("No WebSocket event.")

        assertEquals("open 101", next())
        socket.send("hello through the bridge")
        assertEquals("hello through the bridge", next())
        val big = ByteArray(200_000) { (it % 251).toByte() }
        socket.send(big.toByteString())
        assertArrayEquals(big, (next() as ByteString).toByteArray())
        socket.close(1000, "done")
        assertEquals("closing 1000 done", next())

        val handshake = up.take()
        assertEquals("GET /term?x=1 HTTP/1.1", handshake.requestLine)
        assertEquals("localhost:${up.port}", handshake.header("Host"))
        assertEquals("http://localhost:${up.port}", handshake.header("Origin"))
        assertEquals("s3cret", handshake.header("X-Pocket-Secret"))
        assertEquals("websocket", handshake.header("Upgrade"))
        assertEquals("Upgrade", handshake.header("Connection"))
        assertEquals(null, handshake.header("Cookie"))
        assertNotNull(handshake.header("Sec-WebSocket-Key"))
    }

    @Test fun `a websocket stays open far longer than the idle timeout`() {
        val port = bridge.expose(upstream(::echoWebSocket).port, "terminal")
        openRaw(port).use { (socket, input) ->
            writeFrame(socket.getOutputStream(), TEXT, "before".toByteArray(), masked = true)
            assertEquals("before", String(readFrame(input)!!.payload))
            Thread.sleep(limits.idleTimeoutMs * 4L)
            writeFrame(socket.getOutputStream(), TEXT, "after".toByteArray(), masked = true)
            assertEquals("after", String(readFrame(input)!!.payload))
        }
    }

    @Test fun `half closing one direction keeps the other open`() {
        val up = upstream { exchange ->
            exchange.output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
            exchange.output.flush()
            val received = exchange.input.readBytes()
            exchange.output.write("got ${received.size} bytes, bye".toByteArray())
            exchange.output.flush()
        }
        val port = bridge.expose(up.port, "terminal")
        openRaw(port).use { (socket, input) ->
            socket.getOutputStream().write("0123456789".toByteArray())
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            assertEquals("got 10 bytes, bye", String(input.readBytes()))
        }
    }

    @Test fun `bytes sent right behind the handshake are not lost`() {
        val port = bridge.expose(upstream(::echoWebSocket).port, "terminal")
        Socket("127.0.0.1", port.bridgePort).use { socket ->
            socket.soTimeout = 5_000
            val frame = ByteArrayOutputStream().also { writeFrame(it, TEXT, "early".toByteArray(), masked = true) }.toByteArray()
            socket.getOutputStream().write(handshake(port).toByteArray() + frame)
            val input = BufferedInputStream(socket.getInputStream())
            assertTrue(readHead(input)!!.startsWith("HTTP/1.1 101"))
            assertEquals("early", String(readFrame(input)!!.payload))
        }
    }

    @Test fun `a refused upgrade comes back as an ordinary response`() {
        val up = upstream { it.reply(403, "no sockets here") }
        val port = bridge.expose(up.port, "terminal")
        val response = rawExchange(port.bridgePort, handshake(port))
        assertEquals(403, response.status)
        assertEquals("no sockets here", response.bodyText)
        assertEquals("close", response.header("Connection"))
    }

    @Test fun `a websocket opened by a page on another port is refused`() {
        val up = upstream(::echoWebSocket)
        val port = bridge.expose(up.port, "terminal")
        val response = rawExchange(port.bridgePort, handshake(port, origin = "http://127.0.0.1:${up.port}"))
        assertEquals(403, response.status)
        assertTrue(up.nothingArrives())
    }

    @Test fun `a handshake that claims a body is refused`() {
        val up = upstream(::echoWebSocket)
        val port = bridge.expose(up.port, "terminal")
        val request = handshake(port).removeSuffix("\r\n") + "Content-Length: 5\r\n\r\nhello"
        assertEquals(400, rawExchange(port.bridgePort, request).status)
        assertTrue(up.nothingArrives())
    }

    private fun handshake(port: BridgedPort, origin: String = port.origin) = head(
        "GET /ws HTTP/1.1", port.host, port.cookie, "Origin" to origin, "Connection" to "keep-alive, Upgrade",
        "Upgrade" to "websocket", "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version" to "13",
    )

    private class RawSocket(val socket: Socket, val input: InputStream) : AutoCloseable {
        operator fun component1() = socket

        operator fun component2() = input

        override fun close() = socket.close()
    }

    private fun openRaw(port: BridgedPort): RawSocket {
        val socket = Socket("127.0.0.1", port.bridgePort)
        socket.soTimeout = 5_000
        socket.getOutputStream().write(handshake(port).toByteArray())
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input)!!
        assertTrue(head, head.startsWith("HTTP/1.1 101"))
        return RawSocket(socket, input)
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    /** A server side of RFC 6455, small but real: it checks masking and answers pings and close. */
    private fun echoWebSocket(exchange: Exchange) {
        val key = exchange.request.header("Sec-WebSocket-Key") ?: return exchange.reply(400, "no key")
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray()))
        exchange.output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        exchange.output.flush()
        while (true) {
            val frame = readFrame(exchange.input, requireMask = true) ?: return
            when (frame.opcode) {
                TEXT, BINARY -> writeFrame(exchange.output, frame.opcode, frame.payload, masked = false)
                PING -> writeFrame(exchange.output, PONG, frame.payload, masked = false)
                CLOSE -> return writeFrame(exchange.output, CLOSE, frame.payload, masked = false)
            }
        }
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, masked: Boolean) {
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> frame.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                frame.write(maskBit or 126)
                frame.write(payload.size shr 8)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) frame.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
            }
        }
        if (masked) {
            val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            frame.write(key)
            frame.write(ByteArray(payload.size) { (payload[it].toInt() xor key[it % 4].toInt()).toByte() })
        } else {
            frame.write(payload)
        }
        out.write(frame.toByteArray())
        out.flush()
    }

    private fun readFrame(input: InputStream, requireMask: Boolean = false): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.read() shl 8) or input.read()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        val masked = second and 0x80 != 0
        check(masked == requireMask) { "Frames from a client must be masked and frames from a server must not." }
        val key = if (masked) readExactly(input, 4) else null
        val payload = readExactly(input, length.toInt())
        if (key != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
        return Frame(first and 0x0F, payload)
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val TEXT = 1
        const val BINARY = 2
        const val CLOSE = 8
        const val PING = 9
        const val PONG = 10
    }
}
