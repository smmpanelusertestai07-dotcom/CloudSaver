package com.pocketide.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class PortProxyTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val upstreams = mutableListOf<ServerSocket>()
    private var proxy: PortProxy? = null

    @After
    fun close() {
        proxy?.close()
        upstreams.forEach { it.close() }
    }

    @Test
    fun `only an address with this app's key names a Cloud Shell port`() {
        val door = PortProxy(key) { null }
        assertEquals(8080, door.target("8080-$key.localhost:41234"))
        assertEquals(18083, door.target("18083-$key.localhost"))
        assertEquals("the host is not case sensitive", 8081, door.target("8081-${key.uppercase()}.LOCALHOST:1"))
        assertNull("another key", door.target("8080-ffffffffffffffffffffffffffffffff.localhost:41234"))
        assertNull("no key", door.target("8080.localhost:41234"))
        assertNull("not localhost", door.target("8080-$key.example.com"))
        assertNull("a system port", door.target("80-$key.localhost:1"))
        assertNull(door.target(null))
    }

    @Test
    fun `a request goes on as if from Cloud Shell's own localhost, one to each connection`() {
        val raw = "GET /stable/x.js HTTP/1.1\r\nHost: 8080-$key.localhost:4000\r\nOrigin: http://8080-$key.localhost:4000\r\n" +
            "Referer: http://8080-$key.localhost:4000/?folder=/home\r\nConnection: keep-alive\r\nKeep-Alive: timeout=5\r\n" +
            "Accept: */*\r\n\r\n"
        val head = Head.parse(raw.toByteArray())!!
        val sent = String(Rewrite.request(head, 8080), Charsets.ISO_8859_1)
        assertTrue(sent, sent.startsWith("GET /stable/x.js HTTP/1.1\r\n"))
        assertTrue(sent, sent.contains("\r\nHost: localhost:8080\r\n"))
        assertTrue(sent, sent.contains("\r\nOrigin: http://localhost:8080\r\n"))
        assertTrue(sent, sent.contains("\r\nReferer: http://localhost:8080/?folder=/home\r\n"))
        assertTrue(sent, sent.contains("\r\nAccept: */*\r\n"))
        assertTrue(sent, sent.contains("\r\nConnection: close\r\n"))
        assertFalse("the key never reaches Cloud Shell", sent.contains(key))
        assertFalse(sent.contains("Keep-Alive"))
    }

    @Test
    fun `PocketIDE's drop path on any address goes to Cloud Shell's file drop`() {
        val upload = Head.parse("POST /__pocketide/drop/upload?agent=codex&name=a.png HTTP/1.1\r\nHost: 8081-$key.localhost:4000\r\n\r\n".toByteArray())!!
        val (sent, port) = Rewrite.route(upload, 8081)
        assertEquals(PortProxy.DROP_PORT, port)
        assertEquals("POST /upload?agent=codex&name=a.png HTTP/1.1", sent.first)
        assertTrue(String(Rewrite.request(sent, port), Charsets.ISO_8859_1).contains("Host: localhost:${PortProxy.DROP_PORT}\r\n"))
        val page = Head.parse("GET /stable/x.js HTTP/1.1\r\nHost: 8081-$key.localhost:4000\r\n\r\n".toByteArray())!!
        assertEquals("any other path stays with its port", page.first to 8081, Rewrite.route(page, 8081).let { it.first.first to it.second })
        val near = Head.parse("GET /__pocketide/dropped HTTP/1.1\r\nHost: 8081-$key.localhost:4000\r\n\r\n".toByteArray())!!
        assertEquals(8081, Rewrite.route(near, 8081).second)
    }

    @Test
    fun `a WebSocket keeps its upgrade`() {
        val head = Head.parse("GET /ws HTTP/1.1\r\nHost: 8080-$key.localhost:4000\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n".toByteArray())!!
        val sent = String(Rewrite.request(head, 8080), Charsets.ISO_8859_1)
        assertTrue(sent, sent.contains("\r\nConnection: Upgrade\r\n"))
        assertTrue(sent, sent.contains("\r\nUpgrade: websocket\r\n"))
    }

    @Test
    fun `an answer's redirect to Cloud Shell's localhost comes back through the door, and framing allows the door`() {
        assertEquals("http://18083-$key.localhost:4000/auth?x=1", Rewrite.location("http://localhost:18083/auth?x=1", key, 4000))
        assertEquals("http://8080-$key.localhost:4000/", Rewrite.location("http://127.0.0.1:8080", key, 4000))
        assertEquals("https://accounts.google.com/x", Rewrite.location("https://accounts.google.com/x", key, 4000))
        assertEquals("./?folder=/x", Rewrite.location("./?folder=/x", key, 4000))
        assertEquals(
            "default-src 'self'; frame-ancestors http://*.localhost:* 'self' http://localhost:*",
            Rewrite.framing("default-src 'self'; frame-ancestors 'self' http://localhost:*"),
        )
        assertEquals("default-src 'self'", Rewrite.framing("default-src 'self'"))
    }

    @Test
    fun `the door passes a page on both ways, and refuses a request without its key`() {
        val seen = mutableListOf<String>()
        val upstream = server { client ->
            val head = PortProxy.readHead(client.getInputStream())!!
            seen += String(head, Charsets.ISO_8859_1)
            client.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://localhost:8080/?folder=/p\r\nContent-Length: 2\r\n\r\nok".toByteArray())
        }
        val port = start { if (it == 8080) socketDuplex(upstream.localPort) else null }
        val answer = get(port, "8080-$key.localhost:$port", "/")
        assertTrue(answer, answer.startsWith("HTTP/1.1 302 Found\r\n"))
        assertTrue(answer, answer.contains("\r\nLocation: http://8080-$key.localhost:$port/?folder=/p\r\n"))
        assertTrue(answer, answer.endsWith("\r\n\r\nok"))
        assertTrue(seen.single(), seen.single().contains("\r\nHost: localhost:8080\r\n"))

        assertTrue(get(port, "8080-ffffffffffffffffffffffffffffffff.localhost:$port", "/").startsWith("HTTP/1.1 403 "))
        assertTrue("not connected", get(port, "8081-$key.localhost:$port", "/").startsWith("HTTP/1.1 502 "))
    }

    @Test
    fun `a port where nothing answers says so, instead of an empty page`() {
        val silent = server { client -> PortProxy.readHead(client.getInputStream()) }
        val port = start { socketDuplex(silent.localPort) }
        val answer = get(port, "3000-$key.localhost:$port", "/")
        assertTrue(answer, answer.startsWith("HTTP/1.1 502 Nothing answers on this port in Cloud Shell yet"))
    }

    private fun start(open: (Int) -> Duplex?): Int {
        val door = PortProxy(key, open).also { proxy = it }
        return door.start(0).also { assertTrue(it > 0) }
    }

    private fun server(handle: (Socket) -> Unit): ServerSocket {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { upstreams += it }
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(handle) }
            }
        }
        return socket
    }

    private fun get(port: Int, host: String, path: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
        socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    /** A plain TCP connection standing in for the socket file of a Cloud Shell port. */
    private fun socketDuplex(port: Int): Duplex {
        val socket = Socket("127.0.0.1", port)
        return object : Duplex {
            override val input: InputStream = socket.getInputStream()
            override val output: OutputStream = socket.getOutputStream()
            override fun endOutput() = socket.shutdownOutput()
            override fun close() = socket.close()
        }
    }
}
