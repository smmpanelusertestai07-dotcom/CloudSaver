package com.pocketide.rooms

import com.pocketide.core.AppDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The check that keeps Antigravity's hub closed unless it keeps its token from other apps. */
class HubGuardTest {
    private val token = "a".repeat(64)
    private val page = """<script>window.__APP_CONFIG__ = {"productName":"antigravity","csrfToken":"$token"};</script>"""

    @Test fun `a hub that serves its token to a caller without it gives it away`() {
        // What agy 1.2.10 answers to any app that asks for its page.
        val leaky = HttpAnswer(200, page)
        assertEquals(HubGuard.GIVES_TOKEN_AWAY, RoomEngines.hubGuard(leaky, HttpAnswer(200, page), token))
        val inCookie = HttpAnswer(200, "<html></html>", "HTTP/1.1 200 OK\r\nSet-Cookie: csrfToken=$token")
        assertEquals(HubGuard.GIVES_TOKEN_AWAY, RoomEngines.hubGuard(inCookie, HttpAnswer(200, page), token))
    }

    @Test fun `a hub that answers only with the token is guarded`() {
        val refused = HttpAnswer(401, """{"code":"unauthenticated","message":"missing CSRF token"}""")
        assertEquals(HubGuard.GUARDED, RoomEngines.hubGuard(refused, HttpAnswer(200, page), token))
        assertEquals("a page without the token is no leak", HubGuard.GUARDED, RoomEngines.hubGuard(HttpAnswer(200, "<html></html>"), HttpAnswer(200, page), token))
    }

    @Test fun `a hub that refuses its own token, or stops answering, is not opened either`() {
        val refused = HttpAnswer(401, "missing CSRF token")
        assertEquals(HubGuard.REFUSES_TOKEN, RoomEngines.hubGuard(refused, HttpAnswer(401, "invalid CSRF token"), token))
        assertEquals(HubGuard.NO_ANSWER, RoomEngines.hubGuard(null, HttpAnswer(200, page), token))
        assertEquals(HubGuard.NO_ANSWER, RoomEngines.hubGuard(refused, null, token))
    }

    @Test fun `the hub is started with this launch's token, and the bridge sends it in the header its page uses`() {
        val dirs = AppDirs(File("/data/app/files"), File("/data/app/cache"))
        val profile = RoomProfiles.of(RoomProfiles.ANTIGRAVITY, null)!!
        val command = RoomEngines.hub(dirs, profile, "/work/octo__app/a1", 4321, emptyMap(), token)
        assertTrue(command.argv.contains("--csrf_token=$token"))
        assertTrue(command.argv.contains("--hub-port=4321"))
        assertEquals("x-codeium-csrf-token", RoomEngines.HUB_TOKEN_HEADER)
    }

    @Test fun `loopback requests carry extra headers, and keep the answer's head`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var request = ""
            val answering = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    request = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nSet-Cookie: a=b\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                }
            }
            val answer = Loopback.get(server.localPort, "/", headers = mapOf(RoomEngines.HUB_TOKEN_HEADER to token))
            answering.join()
            assertEquals(200, answer?.status)
            assertEquals("ok", answer?.body)
            assertTrue(answer!!.head.contains("Set-Cookie: a=b"))
            assertTrue(request, request.contains("x-codeium-csrf-token: $token"))
            assertTrue(request, request.contains("Host: localhost:${server.localPort}"))
        }
        try {
            Loopback.get(1, "/", headers = mapOf("x" to "y\r\nInjected: 1"))
            throw AssertionError("a header broke the request line")
        } catch (refused: IllegalArgumentException) {
            // expected
        }
    }
}
