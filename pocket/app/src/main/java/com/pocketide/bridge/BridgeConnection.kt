package com.pocketide.bridge

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/** What a connection needs to know about the exposure it arrived on. */
internal class BridgeTarget(
    val targetPort: Int,
    val bridgePort: Int,
    val token: String,
    /** Added to every request for [targetPort]; a "Cookie" entry joins the request's cookies. */
    val inject: List<Pair<String, String>>,
) {
    val cookieName = BridgeAccess.cookieName(bridgePort)
}

/** The bridge as a whole, as one connection sees it. */
internal interface BridgeDirectory {
    /** True when [port] is exposed, so "<port>.localhost" may be routed to it. */
    fun isExposed(port: Int): Boolean

    /** The bridge ports open right now. */
    fun liveBridgePorts(): Set<Int>

    /** Data went through [target]'s bridge to [port]. */
    fun traffic(target: BridgeTarget, port: Int) = Unit
}

/**
 * One client connection: exactly one request (or one WebSocket), checked, forwarded and
 * answered, then closed. Closing after every request is what makes each request pass the
 * cookie check on its own: nothing can ride in behind an authenticated one.
 */
internal class BridgeConnection(
    private val client: Socket,
    private val target: BridgeTarget,
    private val directory: BridgeDirectory,
    private val limits: BridgeLimits,
) : Closeable {
    @Volatile private var upstream: Socket? = null
    @Volatile private var lastProgress = System.nanoTime()
    @Volatile private var idleLimited = true
    @Volatile private var closed = false
    @Volatile private var responseStarted = false

    /** The port this connection forwards to, once known; used when that port is revoked. */
    @Volatile var routedPort: Int? = null
        private set

    private sealed interface Decision {
        class Refuse(val status: Int, val message: String) : Decision
        class Enter(val reply: ByteArray) : Decision
        class Forward(val port: Int, val host: String, val inject: List<Pair<String, String>>) : Decision
    }

    suspend fun serve() {
        try {
            exchange()
        } catch (e: IOException) {
            // One side went away mid-way; there is nobody left to answer.
        } finally {
            close()
        }
    }

    /** Ends a plain exchange that has moved no bytes for too long (a peer that stopped reading). */
    fun closeIfStalled(now: Long) {
        if (idleLimited && now - lastProgress > limits.stallNanos) close()
    }

    override fun close() {
        closed = true
        closeQuietly(client)
        upstream?.let(::closeQuietly)
    }

    private suspend fun exchange() {
        client.tcpNoDelay = true
        val input = HttpInput(client.getInputStream(), limits.maxHeadBytes)
        val request = try {
            readRequest(input) ?: return
        } catch (e: HttpProtocolException) {
            return refuse(e.status, if (e.status == 431) BridgeReplies.TOO_LARGE else BridgeReplies.UNREADABLE)
        } catch (e: SocketTimeoutException) {
            return refuse(408, BridgeReplies.TOO_SLOW)
        }
        val withBody = request.method != "HEAD"
        when (val decision = decide(request)) {
            is Decision.Refuse -> refuse(decision.status, decision.message, withBody)
            is Decision.Enter -> {
                responseStarted = true
                client.getOutputStream().apply { write(decision.reply); flush() }
            }
            is Decision.Forward -> forward(request, input, decision)
        }
    }

    /** The request head, within the size, field-count and 10-second limits. */
    private fun readRequest(input: HttpInput): RequestHead? {
        val deadline = System.nanoTime() + limits.headTimeoutMs * NANOS_PER_MS
        val head = input.readHead(limits.maxHeadBytes) {
            val left = (deadline - System.nanoTime()) / NANOS_PER_MS
            if (left <= 0) throw SocketTimeoutException("The request head took too long.")
            client.soTimeout = left.toInt()
        } ?: return null
        return parseRequestHead(head, limits.maxRequestFields)
    }

    private fun decide(request: RequestHead): Decision {
        val hosts = request.headers.all("Host")
        if (hosts.size != 1) return Decision.Refuse(400, BridgeReplies.UNREADABLE)
        val host = hosts.single().trim().lowercase()
        val port = when (val route = BridgeAccess.route(host, target.bridgePort)) {
            null -> return Decision.Refuse(421, BridgeReplies.NOT_SERVED_HERE)
            Route.Main -> target.targetPort
            is Route.Sub ->
                route.port.takeIf(directory::isExposed) ?: return Decision.Refuse(403, BridgeReplies.PORT_NOT_SHARED)
        }
        if (request.path == BridgeAccess.ENTRY_PATH) return enter(request)
        if (!BridgeAccess.hasValidCookie(request.headers, target.cookieName, target.token)) {
            return Decision.Refuse(403, BridgeReplies.NOT_INSIDE_APP)
        }
        if (!BridgeAccess.sameOriginOnly(request.headers, host)) return Decision.Refuse(403, BridgeReplies.OTHER_PAGE)
        // Injected headers belong to this bridge's own target, never to another port it routes to.
        val inject = if (port == target.targetPort) target.inject else emptyList()
        return Decision.Forward(port, host, inject)
    }

    /** GET /_pocketide/enter?t=<token>&next=<path>: sets the cookie and sends the browser on. */
    private fun enter(request: RequestHead): Decision {
        if (request.method != "GET" && request.method != "HEAD") return Decision.Refuse(405, BridgeReplies.UNREADABLE)
        val parameters = try {
            BridgeAccess.queryParameters(request.query)
        } catch (e: IllegalArgumentException) {
            return Decision.Refuse(400, BridgeReplies.UNREADABLE)
        }
        val token = parameters["t"]?.singleOrNull()
        if (token == null || !BridgeAccess.sameSecret(token, target.token)) {
            return Decision.Refuse(403, BridgeReplies.LINK_EXPIRED)
        }
        val next = parameters["next"] ?: listOf("/")
        if (next.size != 1 || !BridgeAccess.isSafeNext(next.single())) {
            return Decision.Refuse(400, BridgeReplies.NEXT_NOT_ALLOWED)
        }
        val stale = BridgeAccess.staleCookies(request.headers, directory.liveBridgePorts())
        return Decision.Enter(BridgeReplies.entry(next.single(), target.cookieName, target.token, stale))
    }

    private suspend fun forward(request: RequestHead, clientIn: HttpInput, plan: Decision.Forward) {
        val websocket = isWebSocketUpgrade(request)
        val framing = try {
            requestFraming(request.headers)
        } catch (e: HttpProtocolException) {
            return refuse(e.status, BridgeReplies.UNREADABLE)
        }
        // A handshake has no body; one that claims to could smuggle bytes past the upgrade.
        if (websocket && framing != Framing.None) return refuse(400, BridgeReplies.UNREADABLE)
        routedPort = plan.port
        val socket = try {
            connect(plan.port)
        } catch (e: SocketTimeoutException) {
            return refuse(504, BridgeReplies.noAnswer(plan.port))
        } catch (e: IOException) {
            return refuse(502, BridgeReplies.notRunning(plan.port))
        }
        socket.soTimeout = limits.idleTimeoutMs
        client.soTimeout = limits.idleTimeoutMs
        val upIn = HttpInput(socket.getInputStream(), limits.maxHeadBytes)
        val upOut = socket.getOutputStream()
        upOut.write(upstreamHead(request, plan, websocket).encode())
        touch()
        if (websocket) {
            relayUpgrade(clientIn, upIn, upOut, plan)
        } else {
            relayExchange(request, clientIn, framing, upIn, upOut, plan)
        }
    }

    /** A plain request: the body streams up while the response streams down. */
    private suspend fun relayExchange(
        request: RequestHead,
        clientIn: HttpInput,
        framing: Framing,
        upIn: HttpInput,
        upOut: OutputStream,
        plan: Decision.Forward,
    ) = coroutineScope {
        val badBody = AtomicReference<HttpProtocolException>()
        if (framing == Framing.None) upOut.flush()
        val pump = if (framing == Framing.None) null else launch {
            try {
                copyBody(clientIn, upOut, framing, ByteArray(COPY_BUFFER), ::touch)
            } catch (e: HttpProtocolException) {
                badBody.set(e)
                upstream?.let(::closeQuietly)
            } catch (e: EOFException) {
                close()
            } catch (e: IOException) {
                // The server stopped reading, usually because it has already answered.
            }
        }
        try {
            val response = readResponse(upIn)
            sendResponse(response, upIn, request.method, plan)
        } catch (e: IOException) {
            when {
                responseStarted || closed -> Unit
                badBody.get() != null -> refuse(400, BridgeReplies.UNREADABLE)
                e is SocketTimeoutException -> refuse(504, BridgeReplies.noAnswer(plan.port))
                else -> refuse(502, BridgeReplies.badAnswer(plan.port))
            }
        } finally {
            close()
        }
        pump?.join()
    }

    /** The final response head; interim 1xx answers are passed on as they come. */
    private fun readResponse(upIn: HttpInput): ResponseHead {
        while (true) {
            val head = upIn.readHead(limits.maxHeadBytes) ?: throw EOFException("The server closed without answering.")
            val response = parseResponseHead(head, limits.maxResponseFields)
            if (response.status == 101) throw HttpProtocolException(502, "Switched protocols without being asked.")
            if (response.status >= 200) return response
            responseStarted = true
            client.getOutputStream().apply { write(response.encode()); flush() }
            touch()
        }
    }

    private fun sendResponse(response: ResponseHead, upIn: HttpInput, method: String, plan: Decision.Forward) {
        val framing = responseFraming(method, response)
        rewriteResponse(response.headers, plan)
        responseStarted = true
        val out = client.getOutputStream()
        out.write(response.encode())
        copyBody(upIn, out, framing, ByteArray(COPY_BUFFER), ::touch)
    }

    /** Forwards the handshake; after a 101 both directions are piped until each side ends. */
    private suspend fun relayUpgrade(clientIn: HttpInput, upIn: HttpInput, upOut: OutputStream, plan: Decision.Forward) {
        upOut.flush()
        val response = try {
            val head = upIn.readHead(limits.maxHeadBytes) ?: throw EOFException("The server closed without answering.")
            parseResponseHead(head, limits.maxResponseFields)
        } catch (e: SocketTimeoutException) {
            return refuse(504, BridgeReplies.noAnswer(plan.port))
        } catch (e: IOException) {
            return if (closed) Unit else refuse(502, BridgeReplies.badAnswer(plan.port))
        }
        if (response.status != 101) {
            // The server said no: its answer goes back as an ordinary response.
            return try {
                sendResponse(response, upIn, "GET", plan)
            } catch (e: HttpProtocolException) {
                refuse(502, BridgeReplies.badAnswer(plan.port))
            }
        }
        dropBridgeCookies(response.headers)
        responseStarted = true
        val clientOut = client.getOutputStream()
        clientOut.write(response.encode())
        clientOut.flush()
        // A WebSocket may sit quiet for a long time; it ends only when a side closes.
        idleLimited = false
        client.soTimeout = 0
        upstream?.soTimeout = 0
        coroutineScope {
            launch { pipe(clientIn, upOut) { upstream?.shutdownOutput() } }
            launch { pipe(upIn, clientOut) { client.shutdownOutput() } }
        }
    }

    /** Copies until [from] ends, then half-closes the far side; a broken side ends both. */
    private fun pipe(from: InputStream, to: OutputStream, halfClose: () -> Unit) {
        try {
            copyToEnd(from, to, ByteArray(COPY_BUFFER)) {}
            halfClose()
        } catch (e: IOException) {
            close()
        }
    }

    private fun upstreamHead(request: RequestHead, plan: Decision.Forward, websocket: Boolean): RequestHead {
        val headers = request.headers.copy()
        val listed = headers.tokens("Connection")
        HOP_BY_HOP.forEach(headers::remove)
        if (!websocket) headers.remove("Upgrade")
        listed.filter { it !in END_TO_END }.forEach(headers::remove)
        // The bridge is the only proxy here. Servers such as code-server trust these fields over
        // Host (and derive their base path from them), so a page may not supply its own.
        headers.removeIf { name, _ -> isProxyField(name) }
        headers.set("Host", "localhost:${plan.port}")
        if (headers.has("Origin")) headers.set("Origin", "http://localhost:${plan.port}")
        headers.rewrite("Referer") { rebase(it, from = "http://${plan.host}", to = "http://localhost:${plan.port}") ?: it }
        val injectedCookie = plan.inject.filter { it.first.equals("Cookie", ignoreCase = true) }
            .joinToString("; ") { it.second }.ifEmpty { null }
        headers.remove("Cookie")
        BridgeAccess.forwardedCookie(request.headers, injectedCookie)?.let { headers.add("Cookie", it) }
        plan.inject.filterNot { it.first.equals("Cookie", ignoreCase = true) }.forEach { (name, value) -> headers.set(name, value) }
        headers.add("Connection", if (websocket) "Upgrade" else "close")
        return RequestHead(request.method, request.target, headers)
    }

    private fun rewriteResponse(headers: HeaderList, plan: Decision.Forward) {
        dropBridgeCookies(headers)
        val listed = headers.tokens("Connection")
        HOP_BY_HOP.forEach(headers::remove)
        headers.remove("Upgrade")
        listed.filter { it !in END_TO_END }.forEach(headers::remove)
        // RFC 9112 §6.3: with a transfer coding present, a received length must not be passed on.
        if (headers.has("Transfer-Encoding")) headers.remove("Content-Length")
        val bridgeOrigin = "http://${plan.host}"
        for (name in listOf("Location", "Content-Location")) {
            headers.rewrite(name) { value -> targetOrigins(plan.port).firstNotNullOfOrNull { rebase(value, it, bridgeOrigin) } ?: value }
        }
        headers.add("Connection", "close")
    }

    /** A server behind the bridge may never set, or overwrite, a bridge's own cookie. */
    private fun dropBridgeCookies(headers: HeaderList) {
        headers.rewrite("Set-Cookie") { value -> value.takeUnless { it.trimStart().startsWith(BridgeAccess.COOKIE_PREFIX) } }
    }

    private fun connect(port: Int): Socket {
        val refused = try {
            return open(LOOPBACK_V4, port)
        } catch (e: ConnectException) {
            e
        }
        // A server that listens on "localhost" may have bound only the IPv6 loopback.
        return try {
            open(LOOPBACK_V6, port)
        } catch (e: IOException) {
            throw refused
        }
    }

    private fun open(address: InetAddress, port: Int): Socket {
        val socket = Socket()
        upstream = socket
        if (closed) {
            closeQuietly(socket)
            throw SocketException("The connection was closed.")
        }
        try {
            socket.connect(InetSocketAddress(address, port), limits.connectTimeoutMs)
            socket.tcpNoDelay = true
            return socket
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
    }

    /**
     * Answers with a small page and closes. The client may still be sending; closing with its
     * bytes unread would reset the connection and could wipe out the answer before it is read.
     */
    private fun refuse(status: Int, message: String, withBody: Boolean = true) {
        if (responseStarted || closed) return
        responseStarted = true
        try {
            client.getOutputStream().apply {
                write(BridgeReplies.page(status, message, withBody))
                flush()
            }
            client.shutdownOutput()
            client.soTimeout = LINGER_MS
            val input = client.getInputStream()
            val sink = ByteArray(COPY_BUFFER)
            var drained = 0
            while (drained < LINGER_BYTES) {
                val read = input.read(sink)
                if (read < 0) break
                drained += read
            }
        } catch (e: IOException) {
            // Gone already, or slow to finish: the page was written either way.
        }
        close()
    }

    private fun touch() {
        lastProgress = System.nanoTime()
        routedPort?.let { directory.traffic(target, it) }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val COPY_BUFFER = 16 * 1024
        const val LINGER_MS = 2_000
        const val LINGER_BYTES = 256 * 1024

        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val LOOPBACK_V6: InetAddress = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

        /** Hop-by-hop fields (RFC 9110 §7.6.1); Upgrade is handled on its own. */
        val HOP_BY_HOP = listOf("Connection", "Keep-Alive", "Proxy-Connection", "TE", "Trailer", "Proxy-Authorization")

        /** Never dropped because a Connection field lists them: that would unframe or unguard a message. */
        val END_TO_END = setOf(
            "host", "content-length", "transfer-encoding", "cookie", "set-cookie", "origin", "upgrade",
            "location", "sec-websocket-key", "sec-websocket-version", "sec-websocket-protocol",
            "sec-websocket-extensions", "sec-websocket-accept",
        )

        fun isProxyField(name: String): Boolean {
            val lower = name.lowercase()
            return lower == "forwarded" || lower.startsWith("x-forwarded-") || lower == "x-real-ip" || lower == "x-original-host"
        }

        fun isWebSocketUpgrade(request: RequestHead): Boolean =
            request.method == "GET" &&
                "upgrade" in request.headers.tokens("Connection") &&
                "websocket" in request.headers.tokens("Upgrade")

        fun targetOrigins(port: Int) = listOf(
            "http://localhost:$port", "http://127.0.0.1:$port", "http://[::1]:$port",
            "//localhost:$port", "//127.0.0.1:$port",
        )

        /** [value] with the origin [from] replaced by [to]; null when it is not on [from]. */
        fun rebase(value: String, from: String, to: String): String? {
            if (value.length < from.length || !value.regionMatches(0, from, 0, from.length, ignoreCase = true)) return null
            val rest = value.substring(from.length)
            return if (rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#') to + rest else null
        }
    }
}

internal fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (e: IOException) {
        // Already closed, or closing failed; either way it is no longer in use.
    }
}
