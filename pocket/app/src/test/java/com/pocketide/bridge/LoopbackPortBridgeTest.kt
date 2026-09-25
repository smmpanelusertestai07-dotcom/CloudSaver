package com.pocketide.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LoopbackPortBridgeTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val limits = BridgeLimits(headTimeoutMs = 700, connectTimeoutMs = 1_000, idleTimeoutMs = 1_500, reaperIntervalMs = 200)
    private val bridge = LoopbackPortBridge(limits)
    private val upstreams = mutableListOf<TestUpstream>()

    @After fun tearDown() {
        bridge.shutdown()
        upstreams.forEach(TestUpstream::close)
    }

    private fun upstream(handle: (Exchange) -> Unit = { it.reply(200, "ok") }) = TestUpstream(handle).also { upstreams += it }

    private fun get(port: BridgedPort, path: String = "/", vararg extra: Pair<String, String>): RawResponse =
        rawExchange(port.bridgePort, head("GET $path HTTP/1.1", port.host, port.cookie, *extra))

    @Test fun `entry sets a strict HttpOnly cookie and redirects to the root`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        assertEquals("http://127.0.0.1:${port.bridgePort}", port.origin)
        assertTrue(port.entryUrl.startsWith("${port.origin}/_pocketide/enter?t="))

        val entry = rawExchange(port.bridgePort, head("GET /_pocketide/enter?t=${port.token} HTTP/1.1", port.host))

        assertEquals(302, entry.status)
        assertEquals("/", entry.header("Location"))
        assertEquals(listOf("pide_${port.bridgePort}=${port.token}; Path=/; HttpOnly; SameSite=Strict"), entry.all("Set-Cookie"))
        assertEquals("no-store", entry.header("Cache-Control"))
        assertTrue(up.nothingArrives())

        val page = get(port)
        assertEquals(200, page.status)
        assertEquals("ok", page.bodyText)
    }

    @Test fun `traffic through a bridge is reported for its port, not for refused requests`() {
        val heard = LinkedBlockingQueue<Pair<String, Int>>()
        bridge.onTraffic { bridged, port -> heard += bridged.purpose to port }
        val up = upstream()
        val port = bridge.expose(up.port, "preview:s1")

        rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host))
        assertNull("a refused request is no work", heard.poll(300, TimeUnit.MILLISECONDS))

        assertEquals(200, get(port).status)
        assertEquals("preview:s1" to up.port, heard.poll(5, TimeUnit.SECONDS))
        assertEquals(200, get(port).status)
        assertNull("reported at most twice a minute", heard.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `tokens are long, random and different for every exposed port`() {
        val first = bridge.expose(upstream().port, "a")
        val second = bridge.expose(upstream().port, "b")
        assertEquals(43, first.token.length)
        assertTrue(first.token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(first.token, second.token)
        assertNotEquals(first.bridgePort, second.bridgePort)
    }

    @Test fun `requests without the right cookie get a small page and never reach the server`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")

        val none = rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host))
        val wrong = rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, "Cookie" to "pide_${port.bridgePort}=guess"))
        val otherBridge = rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, "Cookie" to "pide_1=${port.token}"))

        for (response in listOf(none, wrong, otherBridge)) {
            assertEquals(403, response.status)
            assertTrue(response.bodyText.contains("This page opens only inside PocketIDE."))
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertEquals("close", response.header("Connection"))
        }
        assertTrue(up.nothingArrives())
    }

    @Test fun `entry with a wrong or missing token is refused`() {
        val port = bridge.expose(upstream().port, "preview")
        for (target in listOf("/_pocketide/enter?t=nope", "/_pocketide/enter", "/_pocketide/enter?t=${port.token}x")) {
            val response = rawExchange(port.bridgePort, head("GET $target HTTP/1.1", port.host))
            assertEquals(403, response.status)
            assertNull(response.header("Set-Cookie"))
            assertNull(response.header("Location"))
        }
    }

    @Test fun `entry refuses next paths that would leave the origin`() {
        val port = bridge.expose(upstream().port, "preview")
        val unsafe = listOf("//evil.example/", "https://evil.example/", "/\\evil.example", "\\\\evil.example", "/\t/evil.example", "evil", "/a b")
        for (next in unsafe) {
            val target = "/_pocketide/enter?t=${port.token}&next=${URLEncoder.encode(next, "UTF-8")}"
            val response = rawExchange(port.bridgePort, head("GET $target HTTP/1.1", port.host))
            assertEquals("next=$next", 400, response.status)
            assertNull(response.header("Location"))
            assertNull(response.header("Set-Cookie"))
        }
        val twice = rawExchange(port.bridgePort, head("GET /_pocketide/enter?t=${port.token}&next=/a&next=/b HTTP/1.1", port.host))
        assertEquals(400, twice.status)
    }

    @Test fun `entry can land on a path of the same origin`() {
        val port = bridge.expose(upstream().port, "editor")
        val url = port.entryUrlTo("/?folder=%2Fwork%2Fdemo")
        val response = rawExchange(port.bridgePort, head("GET ${url.removePrefix(port.origin)} HTTP/1.1", port.host))
        assertEquals(302, response.status)
        assertEquals("/?folder=%2Fwork%2Fdemo", response.header("Location"))
    }

    @Test fun `entry clears the cookies of bridges that are gone and keeps the live ones`() {
        val port = bridge.expose(upstream().port, "preview")
        val other = bridge.expose(upstream().port, "editor")
        val cookies = "pide_1234=old; pide_${other.bridgePort}=${other.token}; pide_x=1; theirs=1"
        val response = rawExchange(port.bridgePort, head("GET /_pocketide/enter?t=${port.token} HTTP/1.1", port.host, "Cookie" to cookies))
        val setCookies = response.all("Set-Cookie")
        assertTrue(setCookies.contains("pide_1234=; Path=/; Max-Age=0"))
        assertFalse(setCookies.any { it.startsWith("pide_${other.bridgePort}=") })
        assertFalse(setCookies.any { it.startsWith("pide_x=") || it.startsWith("theirs=") })
    }

    @Test fun `forwarded requests carry the target's own Host and Origin and never a bridge cookie`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val response = rawExchange(
            port.bridgePort,
            head(
                "GET /page?q=1 HTTP/1.1",
                port.host,
                "Cookie" to "a=1; pide_${port.bridgePort}=${port.token}; pide_9999=zzz; b=2",
                "Origin" to port.origin,
                "Referer" to "${port.origin}/before",
                "Connection" to "keep-alive",
                "Keep-Alive" to "timeout=5",
                "X-Forwarded-Host" to "evil.example",
                "Forwarded" to "host=evil.example",
                "X-Forwarded-Prefix" to "/elsewhere",
                "X-Real-IP" to "10.0.0.1",
            ),
        )
        assertEquals(200, response.status)

        val seen = up.take()
        assertEquals("GET /page?q=1 HTTP/1.1", seen.requestLine)
        assertEquals(listOf("localhost:${up.port}"), seen.all("Host"))
        assertEquals("http://localhost:${up.port}", seen.header("Origin"))
        assertEquals("http://localhost:${up.port}/before", seen.header("Referer"))
        assertEquals(listOf("a=1; b=2"), seen.all("Cookie"))
        assertEquals(listOf("close"), seen.all("Connection"))
        for (name in listOf("Keep-Alive", "X-Forwarded-Host", "Forwarded", "X-Forwarded-Prefix", "X-Real-IP")) {
            assertNull(name, seen.header(name))
        }
    }

    @Test fun `injected headers reach the target and replace what the page sent`() {
        val up = upstream()
        val port = bridge.expose(up.port, "terminal", mapOf("X-Pocket-Secret" to "s3cret", "Cookie" to "session=abc"))
        get(port, "/", "X-Pocket-Secret" to "forged", "Cookie" to "session=old; a=1")

        val seen = up.take()
        assertEquals(listOf("s3cret"), seen.all("X-Pocket-Secret"))
        assertEquals(listOf("a=1; session=abc"), seen.all("Cookie"))
    }

    @Test fun `headers the bridge sets itself cannot be injected`() {
        val up = upstream()
        for (name in listOf("Host", "Connection", "Content-Length", "Transfer-Encoding", "Origin")) {
            assertThrows<IllegalArgumentException> { bridge.expose(up.port, "x", mapOf(name to "v")) }
        }
        assertThrows<IllegalArgumentException> { bridge.expose(up.port, "x", mapOf("X-Ok" to "line\r\nInjected: 1")) }
        assertThrows<IllegalArgumentException> { bridge.expose(up.port, "x", mapOf("Bad Name" to "v")) }
        assertThrows<IllegalArgumentException> { bridge.expose(0, "x") }
    }

    @Test fun `redirects to the target's own address come back on the bridge origin`() {
        val location = AtomicReference("")
        val up = upstream { it.reply(302, "", "Location" to location.get()) }
        val port = bridge.expose(up.port, "preview")
        val cases = listOf(
            "http://localhost:${up.port}/next?x=1" to "${port.origin}/next?x=1",
            "http://127.0.0.1:${up.port}" to port.origin,
            "//localhost:${up.port}/scheme-relative" to "${port.origin}/scheme-relative",
            "https://example.com/elsewhere" to "https://example.com/elsewhere",
            "/relative" to "/relative",
            "http://localhost:${up.port}0/other-port" to "http://localhost:${up.port}0/other-port",
        )
        for ((sent, expected) in cases) {
            location.set(sent)
            assertEquals(sent, expected, get(port).header("Location"))
        }
    }

    @Test fun `servers behind the bridge cannot set a bridge cookie`() {
        val up = upstream { it.reply(200, "ok", "Set-Cookie" to "pide_1=evil; Path=/", "Set-Cookie" to "theirs=1; Path=/") }
        val port = bridge.expose(up.port, "preview")
        assertEquals(listOf("theirs=1; Path=/"), get(port).all("Set-Cookie"))
    }

    @Test fun `chunked bodies pass both ways with their framing checked`() {
        val up = upstream { exchange ->
            exchange.output.write(
                ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 99\r\n\r\n" +
                    "3\r\nabc\r\n3;ext=1\r\ndef\r\n0\r\nTrailer-Field: t\r\n\r\n").toByteArray(),
            )
            exchange.output.flush()
        }
        val port = bridge.expose(up.port, "preview")
        val request = head("POST /upload HTTP/1.1", port.host, port.cookie, "Transfer-Encoding" to "chunked") +
            "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"

        val response = rawExchange(port.bridgePort, request)

        val seen = up.take()
        assertTrue(seen.chunked)
        assertEquals("hello world", seen.bodyText)
        assertEquals(200, response.status)
        assertNull(response.header("Content-Length"))
        assertEquals("abcdef", String(readChunked(response.body.inputStream())))
    }

    @Test fun `content-length bodies are streamed to the server`() {
        val up = upstream { it.reply(201, "stored ${it.request.body.size}") }
        val port = bridge.expose(up.port, "preview")
        val body = "x".repeat(200_000)
        val response = rawExchange(port.bridgePort, head("PUT /f HTTP/1.1", port.host, port.cookie, "Content-Length" to body.length.toString()) + body)
        assertEquals(201, response.status)
        assertEquals("stored 200000", response.bodyText)
        assertEquals(body, up.take().bodyText)
    }

    @Test fun `a malformed chunked body is refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val badSize = head("POST / HTTP/1.1", port.host, port.cookie, "Transfer-Encoding" to "chunked") + "zz\r\nhello\r\n0\r\n\r\n"
        val overlong = head("POST / HTTP/1.1", port.host, port.cookie, "Transfer-Encoding" to "chunked") + "2\r\nhello\r\n0\r\n\r\n"
        assertEquals(400, rawExchange(port.bridgePort, badSize).status)
        assertEquals(400, rawExchange(port.bridgePort, overlong).status)
    }

    @Test fun `requests that frame their body twice are refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val both = head("POST / HTTP/1.1", port.host, port.cookie, "Content-Length" to "5", "Transfer-Encoding" to "chunked") + "0\r\n\r\n"
        val twoLengths = head("POST / HTTP/1.1", port.host, port.cookie, "Content-Length" to "1", "Content-Length" to "2") + "ab"
        val gzip = head("POST / HTTP/1.1", port.host, port.cookie, "Transfer-Encoding" to "gzip, chunked") + "0\r\n\r\n"
        for (request in listOf(both, twoLengths, gzip)) assertEquals(400, rawExchange(port.bridgePort, request).status)
        assertTrue(up.nothingArrives())
    }

    @Test fun `every request is closed after its response so no second request can ride along`() {
        val up = upstream { it.reply(200, "first") }
        val port = bridge.expose(up.port, "preview")
        val one = head("GET /one HTTP/1.1", port.host, port.cookie)
        val smuggled = head("GET /two HTTP/1.1", port.host, port.cookie)

        val response = rawExchange(port.bridgePort, one + smuggled)

        assertEquals(200, response.status)
        assertEquals("close", response.header("Connection"))
        assertEquals("first", response.bodyText)
        assertEquals(1, Regex("HTTP/1\\.1 \\d{3}").findAll(response.raw).count())
        val seen = up.take()
        assertEquals("GET /one HTTP/1.1", seen.requestLine)
        assertEquals("close", seen.header("Connection"))
        assertTrue(up.nothingArrives())
    }

    @Test fun `a host naming another exposed port reaches it and the cookie still applies`() {
        val editorServer = upstream { it.reply(200, "editor") }
        val hubServer = upstream { it.reply(200, "hub") }
        val editor = bridge.expose(editorServer.port, "editor", mapOf("X-Pocket-Secret" to "editor-only"))
        bridge.expose(hubServer.port, "hub")
        val subHost = "Host" to "${hubServer.port}.localhost:${editor.bridgePort}"

        assertEquals(403, rawExchange(editor.bridgePort, head("GET / HTTP/1.1", subHost)).status)

        val entry = rawExchange(editor.bridgePort, head("GET /_pocketide/enter?t=${editor.token} HTTP/1.1", subHost))
        assertEquals(302, entry.status)
        val entryUrl = editor.entryUrlTo("/", viaSubPort = hubServer.port)
        assertTrue(entryUrl.startsWith("http://${hubServer.port}.localhost:${editor.bridgePort}/_pocketide/enter?t="))

        val response = rawExchange(editor.bridgePort, head("GET / HTTP/1.1", subHost, editor.cookie))
        assertEquals("hub", response.bodyText)
        val seen = hubServer.take()
        assertEquals("localhost:${hubServer.port}", seen.header("Host"))
        assertNull(seen.header("X-Pocket-Secret"))
        assertTrue(editorServer.nothingArrives(100))
    }

    @Test fun `ports that are not exposed and foreign host names are refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val notExposed = freePort()

        val unshared = rawExchange(port.bridgePort, head("GET / HTTP/1.1", "Host" to "$notExposed.localhost:${port.bridgePort}", port.cookie))
        assertEquals(403, unshared.status)
        assertTrue(unshared.bodyText.contains("not shared"))

        val foreignHosts = listOf("evil.example:${port.bridgePort}", "127.0.0.1:${up.port}", "127.0.0.1", "0${up.port}.localhost:${port.bridgePort}")
        for (host in foreignHosts) {
            assertEquals(host, 421, rawExchange(port.bridgePort, head("GET / HTTP/1.1", "Host" to host, port.cookie)).status)
        }
        assertEquals(400, rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.cookie)).status)
        assertEquals(400, rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, port.host, port.cookie)).status)
        assertTrue(up.nothingArrives())
    }

    @Test fun `requests sent by pages on other ports are refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "editor")

        val crossPort = get(port, "/", "Origin" to "http://127.0.0.1:${up.port}")
        val sameSite = get(port, "/", "Sec-Fetch-Site" to "same-site")
        val opaque = get(port, "/", "Origin" to "null")
        val opaqueCrossSite = get(port, "/", "Origin" to "null", "Sec-Fetch-Site" to "cross-site")
        for (response in listOf(crossPort, sameSite, opaque, opaqueCrossSite)) assertEquals(403, response.status)
        assertTrue(up.nothingArrives())

        assertEquals(200, get(port, "/", "Origin" to port.origin, "Sec-Fetch-Site" to "same-origin").status)
        assertEquals(200, get(port, "/", "Origin" to "null", "Sec-Fetch-Site" to "same-origin").status)
        assertEquals(200, get(port, "/", "Sec-Fetch-Site" to "none").status)
    }

    @Test fun `a port with nothing running gets a readable page`() {
        val closed = freePort()
        val port = bridge.expose(closed, "preview")
        val response = get(port)
        assertEquals(502, response.status)
        assertTrue(response.bodyText.contains("Nothing is answering on port $closed yet."))
    }

    @Test fun `a server that does not answer in time gets a gateway timeout`() {
        val up = upstream { Thread.sleep(limits.idleTimeoutMs + 1_000L) }
        val port = bridge.expose(up.port, "preview")
        val started = System.nanoTime()
        val response = get(port)
        assertEquals(504, response.status)
        assertTrue((System.nanoTime() - started) / 1_000_000 < limits.idleTimeoutMs + 900)
    }

    @Test fun `a client that stops reading is cut off instead of holding the bridge`() {
        val serverWriteFailed = LinkedBlockingQueue<Long>()
        val up = upstream { exchange ->
            exchange.output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            val chunk = ByteArray(64 * 1024)
            try {
                while (true) exchange.output.write(chunk)
            } catch (e: IOException) {
                serverWriteFailed += System.nanoTime()
            }
        }
        val quick = LoopbackPortBridge(limits.copy(idleTimeoutMs = 500, reaperIntervalMs = 100))
        try {
            val port = quick.expose(up.port, "preview")
            Socket("127.0.0.1", port.bridgePort).use { socket ->
                socket.getOutputStream().write(head("GET /endless HTTP/1.1", port.host, port.cookie).toByteArray())
                val started = System.nanoTime()
                // The client never reads: the bridge's writes block until the stall check ends it.
                val failed = serverWriteFailed.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("The bridge held on.")
                assertTrue((failed - started) / 1_000_000 < 8_000)
            }
        } finally {
            quick.shutdown()
        }
    }

    @Test fun `a server that listens only on the IPv6 loopback is still reached`() {
        val server = runCatching { ServerSocket(0, 50, InetAddress.getByName("::1")) }.getOrNull()
            ?: throw AssumptionViolatedException("No IPv6 loopback here.")
        server.use {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        readHead(socket.getInputStream())
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nv6".toByteArray())
                    }
                }
            }
            val port = bridge.expose(server.localPort, "preview")
            assertEquals("v6", get(port).bodyText)
        }
    }

    @Test fun `the bridge port is held on the second loopback too, where localhost names resolve first`() {
        val mirror = InetAddress.getByName("127.0.0.2")
        val mirrored = LoopbackPortBridge(limits, mirrorLoopback = mirror)
        try {
            val up = upstream { it.reply(200, "via the mirror") }
            val port = mirrored.expose(up.port, "editor")

            assertThrows<IOException> { ServerSocket(port.bridgePort, 50, mirror).close() }
            val response = Socket(mirror, port.bridgePort).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(head("GET / HTTP/1.1", "Host" to "localhost:${port.bridgePort}", port.cookie).toByteArray())
                RawResponse.parse(readUntilClosed(socket.getInputStream()))
            }
            assertEquals("via the mirror", response.bodyText)

            mirrored.revoke(up.port)
            assertRefused(port.bridgePort, mirror)
        } finally {
            mirrored.shutdown()
        }
    }

    @Test fun `a server's broken reply becomes a bad gateway page`() {
        val up = upstream { exchange ->
            exchange.output.write("NONSENSE\r\n\r\n".toByteArray())
            exchange.output.flush()
        }
        val port = bridge.expose(up.port, "preview")
        assertEquals(502, get(port).status)
    }

    @Test fun `a request head that arrives too slowly is cut off`() {
        val port = bridge.expose(upstream().port, "preview")
        Socket("127.0.0.1", port.bridgePort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:${port.bridgePort}\r\n".toByteArray())
            val response = RawResponse.parse(readUntilClosed(socket.getInputStream()))
            assertEquals(408, response.status)
        }
    }

    @Test fun `oversized heads and too many header fields are refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val huge = head("GET / HTTP/1.1", port.host, port.cookie, "X-Big" to "a".repeat(70_000))
        val many = head("GET / HTTP/1.1", port.host, port.cookie, *Array(101) { "X-F$it" to "v" })
        assertEquals(431, rawExchange(port.bridgePort, huge).status)
        assertEquals(431, rawExchange(port.bridgePort, many).status)
        assertTrue(up.nothingArrives())
    }

    @Test fun `malformed request heads are refused`() {
        val up = upstream()
        val port = bridge.expose(up.port, "preview")
        val cookie = "${port.cookie.first}: ${port.cookie.second}"
        val bad = listOf(
            "GET http://127.0.0.1/ HTTP/1.1\r\nHost: 127.0.0.1:${port.bridgePort}\r\n$cookie\r\n\r\n",
            "GET / HTTP/2.0\r\nHost: 127.0.0.1:${port.bridgePort}\r\n$cookie\r\n\r\n",
            "GET / HTTP/1.1\r\nHost : 127.0.0.1:${port.bridgePort}\r\n$cookie\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: 127.0.0.1:${port.bridgePort}\r\n folded\r\n$cookie\r\n\r\n",
            "GET  / HTTP/1.1\r\nHost: 127.0.0.1:${port.bridgePort}\r\n$cookie\r\n\r\n",
            "GET / HTTP/1.1\r\nHost: 127.0.0.1:${port.bridgePort}\nX: y\r\n$cookie\r\n\r\n",
        )
        for (request in bad) assertEquals(request, 400, rawExchange(port.bridgePort, request).status)
        assertTrue(up.nothingArrives())
    }

    @Test fun `a head request gets no body`() {
        val up = upstream { it.reply(200, "a body the server should not have sent for HEAD") }
        val port = bridge.expose(up.port, "preview")
        val response = rawExchange(port.bridgePort, head("HEAD / HTTP/1.1", port.host, port.cookie))
        assertEquals(200, response.status)
        assertEquals(0, response.body.size)
        val refused = rawExchange(port.bridgePort, head("HEAD / HTTP/1.1", port.host))
        assertEquals(403, refused.status)
        assertEquals(0, refused.body.size)
    }

    @Test fun `interim responses are passed on before the final one`() {
        val up = upstream { exchange ->
            exchange.output.write("HTTP/1.1 103 Early Hints\r\nLink: </a.css>; rel=preload\r\n\r\n".toByteArray())
            exchange.reply(200, "done")
        }
        val port = bridge.expose(up.port, "preview")
        val response = rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, port.cookie))
        assertEquals(103, response.status)
        assertTrue(response.raw.contains("HTTP/1.1 200 X\r\n"))
        assertTrue(response.raw.endsWith("done"))
    }

    @Test fun `exposing a port again reuses its bridge unless the headers changed`() {
        val up = upstream()
        val first = bridge.expose(up.port, "terminal", mapOf("X-Secret" to "1"))
        assertSame(first, bridge.expose(up.port, "terminal", mapOf("X-Secret" to "1")))

        val second = bridge.expose(up.port, "terminal", mapOf("X-Secret" to "2"))
        assertNotEquals(first.bridgePort, second.bridgePort)
        assertEquals(listOf(second), bridge.exposed)
        assertRefused(first.bridgePort)
        assertEquals(200, get(second).status)
        assertThrows<IllegalArgumentException> { bridge.expose(second.bridgePort, "loop") }
    }

    @Test fun `revoke closes the listener and live connections`() {
        val up = upstream { exchange ->
            exchange.output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
            exchange.output.flush()
            copyToEnd(exchange.input, exchange.output, ByteArray(1024)) { exchange.output.flush() }
        }
        val port = bridge.expose(up.port, "terminal")
        Socket("127.0.0.1", port.bridgePort).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(upgradeHead(port).toByteArray())
            val input = socket.getInputStream()
            assertTrue(readHead(input)!!.startsWith("HTTP/1.1 101"))

            bridge.revoke(up.port)

            assertClosedByPeer(input)
        }
        assertRefused(port.bridgePort)
        assertTrue(bridge.exposed.isEmpty())
        assertFalse(bridge.isInternal(port.origin + "/"))
    }

    @Test fun `revoking a port also ends connections routed to it by host name`() {
        val hub = upstream { exchange ->
            exchange.output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray())
            exchange.output.flush()
            copyToEnd(exchange.input, exchange.output, ByteArray(1024)) { exchange.output.flush() }
        }
        val editor = bridge.expose(upstream().port, "editor")
        bridge.expose(hub.port, "hub")
        Socket("127.0.0.1", editor.bridgePort).use { socket ->
            socket.soTimeout = 5_000
            val request = head(
                "GET /ws HTTP/1.1", "Host" to "${hub.port}.localhost:${editor.bridgePort}", editor.cookie,
                "Connection" to "Upgrade", "Upgrade" to "websocket",
            )
            socket.getOutputStream().write(request.toByteArray())
            val input = socket.getInputStream()
            assertTrue(readHead(input)!!.startsWith("HTTP/1.1 101"))
            bridge.revoke(hub.port)
            assertClosedByPeer(input)
        }
        assertEquals(1, bridge.exposed.size)
    }

    @Test fun `isInternal accepts only this bridge's own origins`() {
        val up = upstream()
        val hub = upstream()
        val port = bridge.expose(up.port, "editor")
        bridge.expose(hub.port, "hub")
        val bp = port.bridgePort

        for (url in listOf("${port.origin}/", port.origin, "http://localhost:$bp/x?y#z", "http://LOCALHOST:$bp/", "http://${hub.port}.localhost:$bp/", port.entryUrl)) {
            assertTrue(url, bridge.isInternal(url))
        }
        val outside = listOf(
            "https://127.0.0.1:$bp/", "http://127.0.0.1:${up.port}/", "http://user@127.0.0.1:$bp/", "http://evil.example:$bp/",
            "http://${freePort()}.localhost:$bp/", "http://127.0.0.2:$bp/", "http://[::1]:$bp/", "javascript:alert(1)",
            "not a url", "http://127.0.0.1/", "ws://127.0.0.1:$bp/", "file:///data/data/com.pocketide/files",
        )
        for (url in outside) assertFalse(url, bridge.isInternal(url))
    }

    @Test fun `too many connections at once are turned away`() {
        val small = LoopbackPortBridge(limits.copy(maxConnections = 2, headTimeoutMs = 5_000))
        try {
            val port = small.expose(upstream().port, "preview")
            val held = List(2) { Socket("127.0.0.1", port.bridgePort) }
            try {
                Thread.sleep(200)
                val response = rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, port.cookie))
                assertEquals(503, response.status)
            } finally {
                held.forEach(Socket::close)
            }
            Thread.sleep(200)
            assertEquals(200, rawExchange(port.bridgePort, head("GET / HTTP/1.1", port.host, port.cookie)).status)
        } finally {
            small.shutdown()
        }
    }

    @Test fun `shutdown closes every bridge and refuses new ones`() {
        val a = bridge.expose(upstream().port, "a")
        val b = bridge.expose(upstream().port, "b")
        bridge.shutdown()
        assertRefused(a.bridgePort)
        assertRefused(b.bridgePort)
        assertTrue(bridge.exposed.isEmpty())
        assertThrows<IllegalStateException> { bridge.expose(upstream().port, "c") }
    }

    @Test fun `a bridged port never prints its token`() {
        val port = bridge.expose(upstream().port, "preview")
        assertFalse(port.toString().contains(port.token))
    }

    private fun upgradeHead(port: BridgedPort) = head(
        "GET /ws HTTP/1.1", port.host, port.cookie, "Connection" to "Upgrade", "Upgrade" to "websocket",
        "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==", "Sec-WebSocket-Version" to "13",
    )

    /** The peer closed or reset the connection; a read that merely timed out does not count. */
    private fun assertClosedByPeer(input: java.io.InputStream) {
        try {
            assertEquals(-1, input.read())
        } catch (e: java.net.SocketTimeoutException) {
            fail("The connection was still open.")
        } catch (e: IOException) {
            // Reset by the bridge: closed as well.
        }
    }

    /**
     * A listener closed while a thread waits in accept() leaves the kernel socket up until that
     * thread wakes, a matter of milliseconds; what connects meanwhile must never be served.
     */
    private fun assertRefused(port: Int, address: InetAddress = InetAddress.getByName("127.0.0.1")) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Socket(address, port).use { socket ->
                    socket.soTimeout = 2_000
                    // A listener that closes between the connect and the write resets the connection.
                    val answer = try {
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
                        socket.getInputStream().read()
                    } catch (e: IOException) {
                        -1
                    }
                    assertEquals("A closed bridge answered on port $port.", -1, answer)
                }
                Thread.sleep(10)
            } catch (e: ConnectException) {
                return
            } catch (e: SocketException) {
                // Reset while connecting: the listener was closing. Not served; look again.
                Thread.sleep(10)
            }
        }
        fail("Port $port still accepts connections.")
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return
        throw AssertionError("Expected ${T::class.java.simpleName}, got $e", e)
    }
    throw AssertionError("Expected ${T::class.java.simpleName}, nothing was thrown.")
}
