package com.pocketide.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

/**
 * Finds the servers listening on this phone, and whether each is open to the Wi-Fi. The
 * computer shares the phone's network, so a dev server bound to 0.0.0.0 can be opened by anyone
 * on the same network; one on 127.0.0.1 only from this phone.
 *
 * The kernel's socket table (/proc/net/tcp and tcp6) answers both questions for every port at
 * once, but Android may not let apps read it. Without it, [candidates][scan] are tried one by
 * one: a connection to the loopback shows the server is there, and one to the phone's own
 * network address shows the Wi-Fi can reach it too.
 */
internal class ListenerScan(
    private val procNet: File = File("/proc/net"),
    /** Only sockets of this user id count (the app's, which the computer runs as); null for all. */
    private val ownUid: Int? = null,
    private val networkAddresses: () -> List<InetAddress> = ::phoneNetworkAddresses,
    private val probeTimeoutMs: Int = 300,
) {
    suspend fun scan(candidates: Collection<Int>): List<PortListener> = withContext(Dispatchers.IO) {
        val table = readSocketTable()
        val found = table ?: probe(candidates.filter { it in 1..65535 }.distinct())
        found.map { (port, onNetwork) -> PortListener(port, onNetwork) }.sortedBy { it.port }
    }

    /** Port to "open to the network", from the kernel; null when the table cannot be read. */
    private fun readSocketTable(): Map<Int, Boolean>? {
        val tables = listOf("tcp" to false, "tcp6" to true).mapNotNull { (name, v6) ->
            try {
                parseSocketTable(File(procNet, name).readText(), v6)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
        if (tables.isEmpty()) return null
        val ports = HashMap<Int, Boolean>()
        for (socket in tables.flatten()) {
            if (ownUid != null && socket.uid != ownUid) continue
            ports[socket.port] = ports[socket.port] == true || !socket.address.isLoopbackAddress
        }
        return ports
    }

    private suspend fun probe(ports: List<Int>): Map<Int, Boolean> = coroutineScope {
        val listening = ports.map { port -> async { port.takeIf { accepts(LOOPBACK_V4, it) || accepts(LOOPBACK_V6, it) } } }
            .awaitAll().filterNotNull()
        val outside = networkAddresses()
        listening.map { port -> async { port to outside.any { accepts(it, port) } } }.awaitAll().toMap()
    }

    private fun accepts(address: InetAddress, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(address, port), probeTimeoutMs) }
        true
    } catch (e: IOException) {
        false
    }

    /** One listening socket from the kernel's table. */
    data class TableEntry(val address: InetAddress, val port: Int, val uid: Int)

    companion object {
        private const val LISTEN = "0A"
        private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val LOOPBACK_V6: InetAddress = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })

        /**
         * The listening sockets of /proc/net/tcp or tcp6. Addresses are printed as 32-bit words
         * in the kernel's byte order, little-endian on every phone PocketIDE runs on.
         * Lines that do not parse are skipped.
         */
        fun parseSocketTable(text: String, v6: Boolean): List<TableEntry> =
            text.lineSequence().drop(1).mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 8 || fields[3] != LISTEN) return@mapNotNull null
                val (hexAddress, hexPort) = fields[1].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val address = decodeAddress(hexAddress, if (v6) 16 else 4) ?: return@mapNotNull null
                val port = hexPort.toIntOrNull(16)?.takeIf { it in 1..65535 } ?: return@mapNotNull null
                val uid = fields[7].toIntOrNull() ?: return@mapNotNull null
                TableEntry(address, port, uid)
            }.toList()

        private fun decodeAddress(hex: String, size: Int): InetAddress? {
            if (hex.length != size * 2) return null
            val bytes = ByteArray(size)
            for (word in 0 until size / 4) {
                val value = hex.substring(word * 8, word * 8 + 8).toLongOrNull(16) ?: return null
                for (i in 0 until 4) bytes[word * 4 + i] = (value shr (8 * i)).toByte()
            }
            return InetAddress.getByAddress(bytes)
        }

        /** The phone's own addresses on its networks (Wi-Fi, mobile, hotspot). */
        fun phoneNetworkAddresses(): List<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && !(it is Inet6Address && it.isLinkLocalAddress) }
        } catch (e: SocketException) {
            emptyList()
        }
    }
}
