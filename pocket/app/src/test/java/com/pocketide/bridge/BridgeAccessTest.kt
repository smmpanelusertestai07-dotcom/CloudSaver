package com.pocketide.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class BridgeAccessTest {
    private fun headers(vararg fields: Pair<String, String>) = HeaderList(fields.toList())

    @Test fun `next paths stay on the same origin`() {
        for (safe in listOf("/", "/a/b?c=d#e", "/?folder=%2Fwork%2Fdemo", "/%2F%2Fevil.example")) {
            assertTrue(safe, BridgeAccess.isSafeNext(safe))
        }
        val unsafe = listOf(
            "", "//evil.example", "/\\evil.example", "\\\\evil.example", "https://evil.example", "evil",
            "/a b", "/\tevil", "/\u0000", "/é", "/" + "a".repeat(4096),
        )
        for (bad in unsafe) assertFalse(bad, BridgeAccess.isSafeNext(bad))
    }

    @Test fun `host fields route to the bridge's target or to an exposed port`() {
        assertEquals(Route.Main, BridgeAccess.route("127.0.0.1:4000", 4000))
        assertEquals(Route.Main, BridgeAccess.route("LocalHost:4000", 4000))
        assertEquals(Route.Sub(5173), BridgeAccess.route("5173.localhost:4000", 4000))
        for (host in listOf("127.0.0.1", "127.0.0.1:4001", "evil.example:4000", "0.localhost:4000", "05173.localhost:4000",
            "65536.localhost:4000", "a.5173.localhost:4000", "[::1]:4000", "localhost.:4000", ".localhost:4000")) {
            assertNull(host, BridgeAccess.route(host, 4000))
        }
    }

    @Test fun `only the page's own origin passes`() {
        val host = "127.0.0.1:4000"
        assertTrue(BridgeAccess.sameOriginOnly(headers(), host))
        assertTrue(BridgeAccess.sameOriginOnly(headers("Origin" to "http://127.0.0.1:4000"), host))
        assertTrue(BridgeAccess.sameOriginOnly(headers("Origin" to "null", "Sec-Fetch-Site" to "same-origin"), host))
        assertTrue(BridgeAccess.sameOriginOnly(headers("Sec-Fetch-Site" to "cross-site"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Origin" to "http://127.0.0.1:4001"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Origin" to "http://localhost:4000"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Origin" to "https://127.0.0.1:4000"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Origin" to "null"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Sec-Fetch-Site" to "same-site"), host))
        assertFalse(BridgeAccess.sameOriginOnly(headers("Origin" to "http://127.0.0.1:4000", "Origin" to "http://127.0.0.1:4000"), host))
    }

    @Test fun `the forwarded cookie drops every bridge cookie and merges injected ones`() {
        val sent = headers("Cookie" to "a=1; pide_4000=t; pide_9=x", "Cookie" to "session=old;b=2")
        assertEquals("a=1; session=old; b=2", BridgeAccess.forwardedCookie(sent, null))
        assertEquals("a=1; b=2; session=new", BridgeAccess.forwardedCookie(sent, "session=new"))
        assertNull(BridgeAccess.forwardedCookie(headers("Cookie" to "pide_4000=t"), null))
        assertEquals("s=1", BridgeAccess.forwardedCookie(headers(), "s=1"))
    }

    @Test fun `the cookie check compares the token for this bridge only`() {
        val token = BridgeAccess.newToken(SecureRandom())
        assertTrue(BridgeAccess.hasValidCookie(headers("Cookie" to "x=1; pide_4000=$token"), "pide_4000", token))
        assertFalse(BridgeAccess.hasValidCookie(headers("Cookie" to "pide_4001=$token"), "pide_4000", token))
        assertFalse(BridgeAccess.hasValidCookie(headers("Cookie" to "pide_4000=${token}x"), "pide_4000", token))
        assertFalse(BridgeAccess.hasValidCookie(headers(), "pide_4000", token))
    }

    @Test fun `stale bridge cookies are the ones whose bridge is gone`() {
        val sent = headers("Cookie" to "pide_1=a; pide_2=b; pide_2=c; pide_x=d; pide_=e; other=f; pide_123456=g")
        assertEquals(listOf("pide_1"), BridgeAccess.staleCookies(sent, setOf(2)))
    }

    @Test fun `query parameters are decoded and broken escapes are refused`() {
        assertEquals(mapOf("t" to listOf("a b"), "next" to listOf("/x?y=1")), BridgeAccess.queryParameters("t=a+b&next=%2Fx%3Fy%3D1"))
        assertThrows<IllegalArgumentException> { BridgeAccess.queryParameters("t=%zz") }
    }

    @Test fun `entry urls land on encoded paths and refuse others`() {
        val port = BridgedPort(8080, 4000, "editor", "http://127.0.0.1:4000/_pocketide/enter?t=abc", "http://127.0.0.1:4000")
        assertEquals("http://127.0.0.1:4000/_pocketide/enter?t=abc&next=%2Fa%3Fb%3D%252F", port.entryUrlTo("/a?b=%2F"))
        assertEquals("http://5173.localhost:4000/_pocketide/enter?t=abc&next=%2F", port.entryUrlTo("/", viaSubPort = 5173))
        assertThrows<IllegalArgumentException> { port.entryUrlTo("//evil.example") }
        assertThrows<IllegalArgumentException> { port.entryUrlTo("/with space") }
    }
}
