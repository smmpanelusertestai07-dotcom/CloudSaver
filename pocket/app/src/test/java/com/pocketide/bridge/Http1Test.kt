package com.pocketide.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class Http1Test {
    private fun input(text: String, capacity: Int = 1024) = HttpInput(text.byteInputStream(Charsets.ISO_8859_1), capacity)

    private fun requestHead(text: String) = parseRequestHead(text.toByteArray(Charsets.ISO_8859_1), maxFields = 100)

    @Test fun `a head is read up to its blank line and the rest stays buffered`() {
        val stream = input("GET / HTTP/1.1\r\nHost: a\r\n\r\nBODY")
        assertEquals("GET / HTTP/1.1\r\nHost: a", String(stream.readHead(512)!!, Charsets.ISO_8859_1))
        assertEquals("BODY", stream.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun `a head split across many small reads is still found`() {
        val source = "GET / HTTP/1.1\r\nHost: a\r\n\r\nX".byteInputStream()
        val trickle = object : java.io.InputStream() {
            override fun read() = source.read()
            override fun read(b: ByteArray, off: Int, len: Int) = source.read(b, off, minOf(len, 1))
        }
        val stream = HttpInput(trickle, 256)
        assertEquals("GET / HTTP/1.1\r\nHost: a", String(stream.readHead(256)!!, Charsets.ISO_8859_1))
        assertEquals('X'.code, stream.read())
    }

    @Test fun `an empty stream has no head and a cut one is an error`() {
        assertNull(input("").readHead(512))
        assertEquals(400, statusOf { input("GET / HTTP/1.1\r\nHost").readHead(512) })
        assertEquals(431, statusOf { input("GET / HTTP/1.1\r\nX: ${"a".repeat(600)}\r\n\r\n").readHead(512) })
    }

    @Test fun `request heads are parsed strictly`() {
        val head = requestHead("POST /a?b=c HTTP/1.1\r\nHost: x\r\nX-Two:  spaced value \t")
        assertEquals("POST", head.method)
        assertEquals("/a", head.path)
        assertEquals("b=c", head.query)
        assertEquals("spaced value", head.headers.first("x-two"))
        for (bad in listOf(
            "GET /\r\nHost: x", "GET * HTTP/1.1", "G@T / HTTP/1.1", "GET /\u0001 HTTP/1.1", "GET / HTTP/1.1\r\n: empty",
            "GET / HTTP/1.1\r\nX\u0000: y", "GET / HTTP/1.1\r\nX: y\u0007", "GET / HTTP/1.1\r\n\tfolded",
        )) {
            assertEquals(bad, 400, statusOf { requestHead(bad) })
        }
    }

    @Test fun `response heads keep their status line and fields`() {
        val response = parseResponseHead("HTTP/1.1 404 Not Found\r\nA: 1\r\nA: 2".toByteArray(), maxFields = 10)
        assertEquals(404, response.status)
        assertEquals(listOf("1", "2"), response.headers.all("a"))
        assertEquals("HTTP/1.1 404 Not Found\r\nA: 1\r\nA: 2\r\n\r\n", String(response.encode()))
        assertEquals(200, parseResponseHead("HTTP/1.0 200".toByteArray(), 10).status)
        for (bad in listOf("HTTP/2 200 OK", "HTTP/1.1 20 OK", "HTTP/1.1 abc OK", "SIP/2.0 200 OK", "HTTP/1.1 099 Low")) {
            assertEquals(bad, 502, statusOf { parseResponseHead(bad.toByteArray(), 10) })
        }
    }

    @Test fun `request framing refuses ambiguity`() {
        assertEquals(Framing.None, requestFraming(HeaderList()))
        assertEquals(Framing.None, requestFraming(HeaderList(listOf("Content-Length" to "0"))))
        assertEquals(Framing.Fixed(12), requestFraming(HeaderList(listOf("Content-Length" to "12"))))
        assertEquals(Framing.Fixed(12), requestFraming(HeaderList(listOf("Content-Length" to "12, 12"))))
        assertEquals(Framing.Chunked, requestFraming(HeaderList(listOf("Transfer-Encoding" to "Chunked"))))
        val refused = listOf(
            listOf("Content-Length" to "1", "Content-Length" to "2"),
            listOf("Content-Length" to "-1"),
            listOf("Content-Length" to "1e3"),
            listOf("Content-Length" to "9".repeat(19)),
            listOf("Content-Length" to "5", "Transfer-Encoding" to "chunked"),
            listOf("Transfer-Encoding" to "gzip"),
            listOf("Transfer-Encoding" to "chunked, chunked"),
        )
        for (fields in refused) assertEquals(fields.toString(), 400, statusOf { requestFraming(HeaderList(fields)) })
    }

    @Test fun `response framing follows RFC 9112`() {
        fun response(status: Int, vararg fields: Pair<String, String>) = ResponseHead("HTTP/1.1 $status X", status, HeaderList(fields.toList()))
        assertEquals(Framing.None, responseFraming("HEAD", response(200, "Content-Length" to "5")))
        assertEquals(Framing.None, responseFraming("GET", response(204)))
        assertEquals(Framing.None, responseFraming("GET", response(304, "Content-Length" to "5")))
        assertEquals(Framing.Chunked, responseFraming("GET", response(200, "Transfer-Encoding" to "gzip, chunked", "Content-Length" to "5")))
        assertEquals(Framing.ToEnd, responseFraming("GET", response(200, "Transfer-Encoding" to "gzip")))
        assertEquals(Framing.Fixed(5), responseFraming("GET", response(200, "Content-Length" to "5")))
        assertEquals(Framing.ToEnd, responseFraming("GET", response(200)))
        assertEquals(502, statusOf { responseFraming("GET", response(200, "Content-Length" to "x")) })
    }

    @Test fun `chunked bodies are copied as they are, trailers included`() {
        val body = "4;name=v\r\nWiki\r\n5\r\npedia\r\n0\r\nExpires: never\r\n\r\nNEXT"
        val stream = input(body)
        val out = ByteArrayOutputStream()
        copyBody(stream, out, Framing.Chunked, ByteArray(3)) {}
        assertEquals(body.removeSuffix("NEXT"), out.toString(Charsets.ISO_8859_1.name()))
        assertEquals("NEXT", stream.readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun `broken chunked bodies are refused`() {
        for (bad in listOf("x\r\nabc\r\n0\r\n\r\n", "3\r\nabcd\r\n0\r\n\r\n", "3\nabc\r\n0\r\n\r\n", "-3\r\nabc\r\n0\r\n\r\n", "${"f".repeat(16)}\r\n")) {
            assertEquals(bad, 400, statusOf { copyBody(input(bad), ByteArrayOutputStream(), Framing.Chunked, ByteArray(8)) {} })
        }
    }

    @Test fun `fixed bodies copy exactly their length`() {
        val stream = input("0123456789")
        val out = ByteArrayOutputStream()
        copyBody(stream, out, Framing.Fixed(4), ByteArray(2)) {}
        assertArrayEquals("0123".toByteArray(), out.toByteArray())
        assertEquals('4'.code, stream.read())
        assertThrows<java.io.EOFException> { copyBody(input("12"), ByteArrayOutputStream(), Framing.Fixed(4), ByteArray(2)) {} }
    }

    @Test fun `header lists keep order and rewrite in place`() {
        val headers = HeaderList(listOf("A" to "1", "B" to "2", "a" to "3"))
        headers.set("a", "x")
        headers.rewrite("B") { null }
        headers.add("C", "keep-alive, Upgrade")
        assertEquals("a: x\r\nC: keep-alive, Upgrade\r\n", StringBuilder().also(headers::writeTo).toString())
        assertEquals(listOf("keep-alive", "upgrade"), headers.tokens("c"))
    }

    private fun statusOf(block: () -> Unit): Int =
        try {
            block()
            -1
        } catch (e: HttpProtocolException) {
            e.status
        }
}
