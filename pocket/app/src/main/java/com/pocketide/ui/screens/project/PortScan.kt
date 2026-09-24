package com.pocketide.ui.screens.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Who can reach a dev server, from the address it listens on (§8, B17). */
enum class Reach(val label: String) {
    /** 127.0.0.1 or ::1: only apps on this phone. */
    PHONE_ONLY("Only this phone"),

    /** 0.0.0.0, :: or a network address: anyone on the same Wi-Fi as well. */
    WIFI("Visible on Wi-Fi"),
}

data class Listener(val port: Int, val reach: Reach)

/**
 * Finding dev servers. The computer shares the phone's network, so a server inside Linux listens
 * on the phone's own addresses. Android 10 and later usually hide /proc/net from apps; then the
 * common ports are tried by connecting, and a server is "visible on Wi-Fi" when it also answers
 * on the phone's Wi-Fi address.
 */
object PortScan {
    private const val LISTEN = "0A"
    private const val CONNECT_MS = 300

    /**
     * Listening TCP sockets in the text of /proc/net/tcp ([v6] false) or /proc/net/tcp6. Lines
     * that do not parse are skipped: the file comes from the kernel, but its format is not ours.
     */
    fun parseProcNet(text: String, v6: Boolean): List<Listener> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 4 || fields[3] != LISTEN) return@mapNotNull null
            val local = fields[1]
            val colon = local.lastIndexOf(':')
            if (colon <= 0) return@mapNotNull null
            val port = local.substring(colon + 1).toIntOrNull(16)?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            val reach = reachOf(local.substring(0, colon), v6) ?: return@mapNotNull null
            Listener(port, reach)
        }.toList()

    /** The reach of a /proc/net local address in hex (each 32-bit word in the kernel's byte order, little-endian). */
    fun reachOf(hex: String, v6: Boolean): Reach? {
        val expected = if (v6) 32 else 8
        if (hex.length != expected || !hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        val bytes = hex.chunked(8).flatMap { word -> word.chunked(2).map { it.toInt(16).toByte() }.reversed() }.toByteArray()
        val address = InetAddress.getByAddress(bytes)
        return if (address.isLoopbackAddress || isMappedLoopback(bytes)) Reach.PHONE_ONLY else Reach.WIFI
    }

    /** ::ffff:127.x.x.x, which Java does not count as loopback. */
    private fun isMappedLoopback(bytes: ByteArray): Boolean =
        bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte() && bytes[12] == 127.toByte()

    /** Listeners from /proc/net, or null when Android does not let the app read it. */
    fun readProcNet(root: File = File("/proc/net")): List<Listener>? {
        val found = listOf("tcp" to false, "tcp6" to true).mapNotNull { (name, v6) ->
            val file = File(root, name)
            try {
                parseProcNet(file.readText(), v6)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
        if (found.isEmpty()) return null
        // A port may listen on both families; the wider reach is the one that matters.
        return found.flatten().groupBy { it.port }.map { (port, list) ->
            Listener(port, if (list.any { it.reach == Reach.WIFI }) Reach.WIFI else Reach.PHONE_ONLY)
        }
    }

    /** True when something accepts a connection on [address]:[port] within a moment. */
    fun answers(address: InetAddress, port: Int, timeoutMs: Int = CONNECT_MS): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    }

    /** Whether [port] also answers on one of [lanAddresses] (the phone's Wi-Fi addresses). */
    fun reachFromLan(port: Int, lanAddresses: List<InetAddress>): Reach =
        if (lanAddresses.any { answers(it, port) }) Reach.WIFI else Reach.PHONE_ONLY

    /**
     * Dev servers on the phone with their reach: every listener /proc/net shows (above the
     * system ports) when it can be read, else those of [candidates] that answer on 127.0.0.1.
     */
    suspend fun scan(candidates: Collection<Int>, procNet: File = File("/proc/net")): Map<Int, Reach> = coroutineScope {
        val listeners = withContext(Dispatchers.IO) { readProcNet(procNet) }
        if (listeners != null) {
            return@coroutineScope listeners.filter { it.port >= FIRST_USER_PORT }.associate { it.port to it.reach }
        }
        val lan = withContext(Dispatchers.IO) { lanAddresses() }
        candidates.distinct()
            .map { port ->
                async(Dispatchers.IO) {
                    if (answers(InetAddress.getLoopbackAddress(), port)) port to reachFromLan(port, lan) else null
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    /** Ports below this belong to the system, never to a dev server an agent started. */
    const val FIRST_USER_PORT = 1024

    /** The phone's own IPv4 addresses on networks that are up, other than loopback. */
    fun lanAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && !it.isLoopbackAddress }
    } catch (_: IOException) {
        emptyList()
    }
}
