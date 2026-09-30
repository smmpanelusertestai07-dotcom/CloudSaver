package com.pocketide.cloudshell

import com.pocketide.link.Duplex
import com.pocketide.link.PortProxy
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Catches a sign-in page's return on this phone. An agent in Cloud Shell that signs in with a
 * browser has the page return to http://localhost:PORT/..., which on a phone is the phone itself.
 * While PocketIDE listens on that port (only on this phone's own address, only for a few minutes):
 *  - connected to Cloud Shell (PocketIDE's own screens), each request goes on, unchanged, to the
 *    agent waiting on the same port in Cloud Shell, and its answer comes back: the agent is signed
 *    in as on a computer;
 *  - otherwise (the Chrome way), the return gets a redirect to the same address in Cloud Shell,
 *    through Web Preview (SignInReturn).
 * When PocketIDE is not listening (Android closed it, or another app has the port), the page says
 * "localhost refused to connect". Nothing it passes on is kept or logged.
 */
object SignInCatcher {
    private const val WAIT_MS = 10 * 60 * 1000
    private const val READ_MS = 5_000

    /** How long the port stays open after the return went through, for the page the agent shows next. */
    private const val AFTER_RETURN_MS = 60_000L
    private val listening = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Listens on [port] for a sign-in's return, for [WAIT_MS]. With [relay] (a connection to
     * Cloud Shell's port), requests go on to the agent there; without, to [account]'s Web Preview.
     */
    fun catchOn(port: Int, account: String, relay: ((Int) -> Duplex?)? = null) {
        if (!SignInReturn.isAgentPort(port) || !listening.add(port)) return
        val server = runCatching { ServerSocket(port, BACKLOG, InetAddress.getByName(PortProxy.LOOPBACK)) }.getOrNull()
        if (server == null) {
            listening.remove(port) // another app has it: the page says "refused", and the tools button still finishes it
            return
        }
        thread(name = "PocketIDE sign-in $port", isDaemon = true) {
            server.use { if (relay != null) relayAll(it, port, relay) else serve(it, port, account) }
            listening.remove(port)
        }
    }

    private fun serve(server: ServerSocket, port: Int, account: String) {
        server.soTimeout = WAIT_MS
        val until = System.currentTimeMillis() + WAIT_MS
        var done = false
        // Chrome may open a connection it never uses, or ask for the icon first: wait for the page itself.
        while (!done && System.currentTimeMillis() < until) {
            // accept() gives up after WAIT_MS (SocketTimeoutException): then nothing came.
            val client = runCatching { server.accept() }.getOrNull() ?: break
            done = client.use { runCatching { answer(it, port, account) }.getOrDefault(false) }
        }
    }

    /** Every request to the port goes on to Cloud Shell until [AFTER_RETURN_MS] after the first one, or [WAIT_MS]. */
    private fun relayAll(server: ServerSocket, port: Int, relay: (Int) -> Duplex?) {
        val until = System.currentTimeMillis() + WAIT_MS
        var closeAt = until
        while (System.currentTimeMillis() < closeAt) {
            server.soTimeout = (closeAt - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            val client = runCatching { server.accept() }.getOrNull() ?: break
            if (closeAt == until) closeAt = minOf(until, System.currentTimeMillis() + AFTER_RETURN_MS)
            thread(name = "PocketIDE sign-in $port relay", isDaemon = true) { client.use { relayOne(it, port, relay) } }
        }
    }

    /** One request and its answer, both ways, unchanged. */
    internal fun relayOne(client: Socket, port: Int, relay: (Int) -> Duplex?) {
        client.soTimeout = READ_MS
        val fromClient = BufferedInputStream(client.getInputStream())
        val head = PortProxy.readHead(fromClient) ?: return
        val upstream = relay(port) ?: return replyNotConnected(client)
        client.soTimeout = 0
        upstream.use {
            it.output.write(head)
            it.output.flush()
            val upload = thread(name = "PocketIDE sign-in up", isDaemon = true) {
                PortProxy.pump(fromClient, it.output) { runCatching { it.endOutput() } }
            }
            PortProxy.pump(it.input, client.getOutputStream()) {}
            runCatching { client.shutdownOutput() }
            upload.join(UPLOAD_GRACE_MS)
        }
    }

    private fun replyNotConnected(client: Socket) {
        val body = "PocketIDE is not connected to Cloud Shell. Open the agent in PocketIDE and sign in again.".toByteArray()
        val head = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
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

    private const val BACKLOG = 8
    private const val UPLOAD_GRACE_MS = 2_000L
}
