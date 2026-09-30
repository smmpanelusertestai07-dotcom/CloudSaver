package com.pocketide.cloudshell

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Catches a sign-in page's return on this phone. An agent in Cloud Shell that signs in with a
 * browser has the page return to http://localhost:PORT/..., which on a phone is the phone itself:
 * while PocketIDE listens on that port (only on this phone's own address, for a few minutes), the
 * return gets a redirect to the same address in Cloud Shell (SignInReturn), and the
 * agent is signed in without a tap. When PocketIDE is not listening (Android closed it, or another
 * app has the port), the page says "localhost refused to connect" and PocketIDE's tools button on
 * it does the same. Nothing it passes on is kept or logged.
 */
object SignInCatcher {
    private const val WAIT_MS = 10 * 60 * 1000
    private const val READ_MS = 5_000
    private val listening = ConcurrentHashMap.newKeySet<Int>()

    /** Listens on [port] for one sign-in return, for [WAIT_MS], and sends it to [account]'s Cloud Shell. */
    fun catchOn(port: Int, account: String) {
        if (!SignInReturn.isAgentPort(port) || !listening.add(port)) return
        val server = runCatching { ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        if (server == null) {
            listening.remove(port) // another app has it: the tools button on the page does the same
            return
        }
        server.soTimeout = WAIT_MS
        thread(name = "PocketIDE sign-in $port", isDaemon = true) {
            server.use { serve(it, port, account) }
            listening.remove(port)
        }
    }

    private fun serve(server: ServerSocket, port: Int, account: String) {
        val until = System.currentTimeMillis() + WAIT_MS
        var done = false
        // Chrome may open a connection it never uses, or ask for the icon first: wait for the page itself.
        while (!done && System.currentTimeMillis() < until) {
            // accept() gives up after WAIT_MS (SocketTimeoutException): then nothing came.
            val client = runCatching { server.accept() }.getOrNull() ?: break
            done = client.use { runCatching { answer(it, port, account) }.getOrDefault(false) }
        }
    }

    /** Answers one request; true when it was the sign-in's return. */
    internal fun answer(client: Socket, port: Int, account: String): Boolean {
        client.soTimeout = READ_MS
        val request = client.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return false
        val target = request.split(' ').getOrNull(1).orEmpty()
        val back = target.takeIf { it.startsWith("/") && !it.startsWith("/favicon") }
            ?.let { SignInReturn.address("http://localhost:$port$it", account) }
        val answer = if (back != null) {
            "HTTP/1.1 302 Found\r\nLocation: $back\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
        client.getOutputStream().apply {
            write(answer.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        return back != null
    }
}
