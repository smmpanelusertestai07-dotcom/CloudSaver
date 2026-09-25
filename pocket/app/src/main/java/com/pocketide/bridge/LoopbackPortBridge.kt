package com.pocketide.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The bridge's limits; tests shorten the timeouts. */
internal data class BridgeLimits(
    val headTimeoutMs: Int = 10_000,
    val connectTimeoutMs: Int = 5_000,
    val idleTimeoutMs: Int = 120_000,
    val maxHeadBytes: Int = 64 * 1024,
    val maxRequestFields: Int = 100,
    val maxResponseFields: Int = 200,
    val maxConnections: Int = 64,
    val maxExposures: Int = 32,
    val reaperIntervalMs: Long = 15_000,
) {
    /**
     * A plain exchange that moved no bytes for this long is stuck, usually in a write (reads
     * give up after [idleTimeoutMs] on their own, with a page when nothing was sent yet).
     */
    val stallNanos: Long get() = idleTimeoutMs * 2 * 1_000_000L

    /** Two blocking directions per connection, plus two accept loops (IPv4, IPv6) per exposure. */
    val threads: Int get() = maxConnections * 2 + maxExposures * 2
}

/**
 * [PortBridge] on plain sockets: one listener on 127.0.0.1 per exposed port, each with its own
 * token. Blocking IO runs on a bounded view of [Dispatchers.IO] reserved for the bridge, so a
 * WebSocket that stays open for hours never takes a thread from the rest of the app.
 *
 * The same port is held on the IPv6 loopback too. Chromium resolves "localhost" and
 * "<n>.localhost" to [::1] first; were that port free there, another app could listen on it and
 * receive an entry URL, token included, meant for the bridge.
 */
internal class LoopbackPortBridge(
    private val limits: BridgeLimits = BridgeLimits(),
    parent: Job? = null,
    private val random: SecureRandom = SecureRandom(),
    /** The second address each bridge port is held on; tests use another IPv4 loopback. */
    private val mirrorLoopback: InetAddress = LOOPBACK_V6,
    private val listenerScan: ListenerScan = ListenerScan(),
) : PortBridge, BridgeDirectory {
    private val job = SupervisorJob(parent)
    private val scope = CoroutineScope(
        job + Dispatchers.IO.limitedParallelism(limits.threads, "port-bridge") +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "A bridge connection failed.", error) },
    )
    private val lock = Any()
    private val byTarget = ConcurrentHashMap<Int, Exposure>()
    private val byBridge = ConcurrentHashMap<Int, Exposure>()
    private val connections: MutableSet<BridgeConnection> = ConcurrentHashMap.newKeySet()
    private val openConnections = AtomicInteger()
    private var reaper: Job? = null
    private var shutDown = false
    private val hasMirror: Boolean by lazy {
        bound(mirrorLoopback, 0)?.also(::closeQuietly) != null
    }

    private class Exposure(val target: BridgeTarget, purpose: String, val servers: List<ServerSocket>) {
        val published = BridgedPort(
            targetPort = target.targetPort,
            bridgePort = target.bridgePort,
            purpose = purpose,
            entryUrl = "http://127.0.0.1:${target.bridgePort}${BridgeAccess.ENTRY_PATH}?t=${target.token}",
            origin = "http://127.0.0.1:${target.bridgePort}",
        )
        val live: MutableSet<BridgeConnection> = ConcurrentHashMap.newKeySet()

        @Volatile var closed = false
    }

    override val exposed: List<BridgedPort>
        get() = byTarget.values.map { it.published }.sortedBy { it.targetPort }

    override fun expose(port: Int, purpose: String, injectHeaders: Map<String, String>): BridgedPort {
        require(port in 1..65535) { "$port is not a port number." }
        val inject = injectHeaders.map { (name, value) -> checkedHeader(name, value) }
        val replaced: Exposure?
        val exposure: Exposure
        synchronized(lock) {
            check(!shutDown) { "The port bridge has been shut down." }
            require(!byBridge.containsKey(port)) { "Port $port is one of the bridge's own ports." }
            val current = byTarget[port]
            if (current != null && current.target.inject == inject) return current.published
            check(current != null || byTarget.size < limits.maxExposures) {
                "Too many ports are open at once. Close a preview, then try again."
            }
            exposure = listen(port, purpose, inject)
            replaced = current?.also { byBridge.remove(it.target.bridgePort) }
            byTarget[port] = exposure
            byBridge[exposure.target.bridgePort] = exposure
            exposure.servers.forEach { server -> scope.launch { acceptLoop(exposure, server) } }
            if (reaper == null) reaper = scope.launch { reapStalled() }
        }
        replaced?.let(::close)
        return exposure.published
    }

    override fun revoke(port: Int) {
        val exposure = synchronized(lock) {
            byTarget.remove(port)?.also { byBridge.remove(it.target.bridgePort) }
        } ?: return
        close(exposure)
        // Connections other bridges routed to this port by host name end with it.
        connections.filter { it.routedPort == port }.forEach(BridgeConnection::close)
    }

    override fun isInternal(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val plainHttp = "http".equals(uri.scheme, ignoreCase = true) && uri.rawUserInfo == null
        if (!plainHttp || !byBridge.containsKey(uri.port)) return false
        return when (val route = uri.host?.lowercase()?.let(BridgeAccess::routeForName)) {
            Route.Main -> true
            is Route.Sub -> isExposed(route.port)
            null -> false
        }
    }

    override suspend fun listeners(candidates: Collection<Int>): List<PortListener> {
        val own = liveBridgePorts()
        return listenerScan.scan(candidates.filter { it !in own }).filter { it.port !in own }
    }

    override fun shutdown() {
        val all = synchronized(lock) {
            shutDown = true
            byTarget.values.toList().also {
                byTarget.clear()
                byBridge.clear()
            }
        }
        all.forEach(::close)
        job.cancel()
    }

    override fun isExposed(port: Int): Boolean = byTarget.containsKey(port)

    override fun liveBridgePorts(): Set<Int> = byBridge.keys.toSet()

    private fun listen(port: Int, purpose: String, inject: List<Pair<String, String>>): Exposure {
        repeat(BIND_ATTEMPTS) {
            val servers = bindBothLoopbacks()
            if (servers != null) {
                val target = BridgeTarget(port, servers.first().localPort, BridgeAccess.newToken(random), inject)
                return Exposure(target, purpose, servers)
            }
        }
        throw IllegalStateException("The bridge could not open a port on this phone.")
    }

    /**
     * A free port on 127.0.0.1, held on [::1] as well; null when another app already has that
     * port on [::1] (try another). A phone without an IPv6 loopback gets the IPv4 one alone:
     * nobody can listen on an address the phone does not have.
     */
    private fun bindBothLoopbacks(): List<ServerSocket>? {
        val v4 = bound(LOOPBACK_V4, 0) ?: throw IllegalStateException("The bridge could not open a port on this phone.")
        if (!hasMirror) return listOf(v4)
        val v6 = bound(mirrorLoopback, v4.localPort)
        if (v6 != null) return listOf(v4, v6)
        closeQuietly(v4)
        return null
    }

    private fun bound(address: InetAddress, port: Int): ServerSocket? {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port), BACKLOG)
            server
        } catch (e: IOException) {
            closeQuietly(server)
            null
        }
    }

    private fun acceptLoop(exposure: Exposure, server: ServerSocket) {
        while (!exposure.closed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            admit(exposure, socket)
        }
    }

    private fun admit(exposure: Exposure, socket: Socket) {
        if (openConnections.incrementAndGet() > limits.maxConnections) {
            openConnections.decrementAndGet()
            turnAway(socket)
            return
        }
        val connection = BridgeConnection(socket, exposure.target, this, limits)
        exposure.live += connection
        connections += connection
        if (exposure.closed) connection.close()
        // The completion handler runs even when shutdown cancelled the job before it started.
        scope.launch { connection.serve() }.invokeOnCompletion {
            connection.close()
            exposure.live -= connection
            connections -= connection
            openConnections.decrementAndGet()
        }
    }

    private fun turnAway(socket: Socket) {
        try {
            socket.getOutputStream().write(BridgeReplies.page(503, BridgeReplies.BUSY))
        } catch (e: IOException) {
            // The client left first.
        }
        closeQuietly(socket)
    }

    /** Runs while anything is bridged; [expose] starts it again under the same lock. */
    private suspend fun reapStalled() {
        while (true) {
            delay(limits.reaperIntervalMs)
            val now = System.nanoTime()
            connections.forEach { it.closeIfStalled(now) }
            synchronized(lock) {
                if (byTarget.isEmpty() && connections.isEmpty()) {
                    reaper = null
                    return
                }
            }
        }
    }

    private fun close(exposure: Exposure) {
        exposure.closed = true
        exposure.servers.forEach(::closeQuietly)
        exposure.live.forEach(BridgeConnection::close)
    }

    private fun checkedHeader(name: String, value: String): Pair<String, String> {
        require(isToken(name)) { "\"$name\" is not a valid header name." }
        require(isFieldValue(value)) { "The value of \"$name\" has characters a header cannot hold." }
        require(name.lowercase() !in RESERVED_HEADERS) { "\"$name\" is set by the bridge itself." }
        return name to value
    }

    private companion object {
        const val TAG = "PocketBridge"
        const val BACKLOG = 50
        const val BIND_ATTEMPTS = 8
        val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val LOOPBACK_V6: InetAddress = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        val RESERVED_HEADERS = setOf(
            "host", "origin", "connection", "upgrade", "content-length", "transfer-encoding",
            "keep-alive", "proxy-connection", "te", "trailer",
        )
    }
}
