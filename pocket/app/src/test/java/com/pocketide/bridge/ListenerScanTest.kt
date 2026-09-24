package com.pocketide.bridge

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

class ListenerScanTest {
    private val closeLater = mutableListOf<AutoCloseable>()
    private val noProc = File("/nonexistent-proc-net")

    /** Stands in for the phone's Wi-Fi address: reached by a server on 0.0.0.0, not by one on 127.0.0.1. */
    private val wifi = InetAddress.getByName("127.0.0.2")

    @After fun tearDown() {
        closeLater.forEach(AutoCloseable::close)
    }

    private fun listen(address: String): Int =
        ServerSocket(0, 5, InetAddress.getByName(address)).also { closeLater += it }.localPort

    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    @Test fun `the kernel's table is read with its byte order and only listening sockets count`() {
        val tcp = listOf(
            header,
            "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 1 1 0000000000000000 100 0 0 10 0",
            "   1: 00000000:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 2 1 0000000000000000 100 0 0 10 0",
            "   2: 0100007F:1F90 0100007F:D431 01 00000000:00000000 00:00000000 00000000 10123        0 3 1 0000000000000000 20 4 30 10 -1",
            "   3: 6401A8C0:1389 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 4 1 0000000000000000 100 0 0 10 0",
            "garbage line",
        ).joinToString("\n")
        val entries = ListenerScan.parseSocketTable(tcp, v6 = false)

        assertEquals(
            listOf("127.0.0.1" to 8080, "0.0.0.0" to 3000, "192.168.1.100" to 5001),
            entries.map { it.address.hostAddress to it.port },
        )
        assertTrue(entries.all { it.uid == 10123 })

        val tcp6 = listOf(
            header,
            "   0: 00000000000000000000000001000000:1F41 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 5 1 0 100 0 0 10 0",
            "   1: 00000000000000000000000000000000:0BB9 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 6 1 0 100 0 0 10 0",
            "   2: 0000000000000000FFFF00000100007F:1F42 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 7 1 0 100 0 0 10 0",
        ).joinToString("\n")
        val entries6 = ListenerScan.parseSocketTable(tcp6, v6 = true)
        assertEquals(listOf(8001, 3001, 8002), entries6.map { it.port })
        assertEquals(listOf(true, false, true), entries6.map { it.address.isLoopbackAddress })
    }

    @Test fun `a readable table lists every listening port of the computer and marks what the Wi-Fi reaches`() {
        val dir = Files.createTempDirectory("procnet").toFile()
        try {
            File(dir, "tcp").writeText(
                listOf(
                    header,
                    "   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 1 1 0 100 0 0 10 0",
                    "   1: 00000000:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 2 1 0 100 0 0 10 0",
                    "   2: 00000000:1388 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10999 0 3 1 0 100 0 0 10 0",
                ).joinToString("\n"),
            )
            File(dir, "tcp6").writeText(
                listOf(
                    header,
                    "   0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 4 1 0 100 0 0 10 0",
                ).joinToString("\n"),
            )
            val found = runBlocking { ListenerScan(procNet = dir, ownUid = 10123, networkAddresses = { emptyList() }).scan(emptyList()) }
            // 8080 is on the loopback over IPv4 but on every address over IPv6; 5000 is another app's.
            assertEquals(listOf(PortListener(3000, onNetwork = true), PortListener(8080, onNetwork = true)), found)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `without the table candidates are probed, and a server on every address is seen from the network`() {
        val loopbackOnly = listen("127.0.0.1")
        val everywhere = listen("0.0.0.0")
        val nothing = freePort()
        val scan = ListenerScan(procNet = noProc, networkAddresses = { listOf(wifi) })

        val found = runBlocking { scan.scan(listOf(loopbackOnly, everywhere, nothing, 0, 70_000)) }

        assertEquals(
            listOf(PortListener(loopbackOnly, onNetwork = false), PortListener(everywhere, onNetwork = true)).sortedBy { it.port },
            found,
        )
    }

    @Test fun `this machine's own table agrees with a probe`() {
        assumeTrue("No readable /proc/net/tcp here.", File("/proc/net/tcp").canRead())
        val loopbackOnly = listen("127.0.0.1")
        val everywhere = listen("0.0.0.0")

        val found = runBlocking { ListenerScan(networkAddresses = { emptyList() }).scan(emptyList()) }

        assertTrue(PortListener(loopbackOnly, onNetwork = false) in found)
        assertTrue(PortListener(everywhere, onNetwork = true) in found)
    }

    @Test fun `the bridge leaves its own ports out`() {
        val bridge = LoopbackPortBridge(listenerScan = ListenerScan(procNet = noProc, networkAddresses = { emptyList() }))
        try {
            val target = listen("127.0.0.1")
            val port = bridge.expose(target, "preview")
            val found = runBlocking { bridge.listeners(listOf(target, port.bridgePort)) }
            assertEquals(listOf(PortListener(target, onNetwork = false)), found)
            assertFalse(found.any { it.port == port.bridgePort })
        } finally {
            bridge.shutdown()
        }
    }
}
