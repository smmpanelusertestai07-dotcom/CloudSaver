package com.pocketide.cloudshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SignInCatcherTest {
    private val account = "dev@example.com"

    @Test
    fun `the sign-in page's return on the phone goes on to Cloud Shell`() {
        val (handled, answer) = ask("/auth/callback?code=ac_1%2F2&state=s")
        assertTrue(handled)
        assertTrue(answer, answer.startsWith("HTTP/1.1 302 Found\r\n"))
        val back = checkNotNull(SignInReturn.address("http://localhost:$lastPort/auth/callback?code=ac_1%2F2&state=s", account))
        assertTrue(answer, answer.contains("\r\nLocation: $back\r\n"))
    }

    @Test
    fun `Chrome's icon request is no sign-in, and the catcher waits on`() {
        val (handled, answer) = ask("/favicon.ico")
        assertFalse(handled)
        assertTrue(answer, answer.startsWith("HTTP/1.1 404 Not Found\r\n"))
    }

    private var lastPort = 0

    /** One request to the catcher's answer, as Chrome sends it: whether it was the return, and the answer. */
    private fun ask(target: String): Pair<Boolean, String> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort.also { lastPort = it }
            Socket("127.0.0.1", port).use { browser ->
                browser.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".toByteArray())
                val handled = server.accept().use { SignInCatcher.answer(it, port, account) }
                handled to browser.getInputStream().bufferedReader().readText()
            }
        }

    @Test
    fun `nothing is caught on the bridge's own port or outside 1024 to 65535`() {
        // catchOn returns at once for these: no socket, no thread.
        listOf(80, SignInReturn.PORT, 70000).forEach { SignInCatcher.catchOn(it, account) }
        assertEquals(8090, SignInReturn.PORT)
    }
}
