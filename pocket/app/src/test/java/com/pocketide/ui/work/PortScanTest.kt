package com.pocketide.ui.work

import com.pocketide.ui.screens.project.Listener
import com.pocketide.ui.screens.project.PortScan
import com.pocketide.ui.screens.project.Reach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortScanTest {
    @get:Rule val temp = TemporaryFolder()

    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    @Test
    fun readsListenersAndTheirReachFromProcNetTcp() {
        val text = listOf(
            header,
            "   0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 1 1 0",
            "   1: 00000000:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 2 1 0",
            "   2: 0101A8C0:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123 0 3 1 0",
            // Established connections are not servers.
            "   3: 0100007F:0BB8 0100007F:D431 01 00000000:00000000 00:00000000 00000000 10123 0 4 1 0",
        ).joinToString("\n")
        assertEquals(
            listOf(Listener(3000, Reach.PHONE_ONLY), Listener(8000, Reach.WIFI), Listener(5173, Reach.WIFI)),
            PortScan.parseProcNet(text, v6 = false),
        )
    }

    @Test
    fun readsIpv6LoopbackAnyAndMappedLoopback() {
        val text = listOf(
            header,
            "   0: 00000000000000000000000001000000:0BB8 00000000000000000000000000000000:0000 0A 0 0 0 0",
            "   1: 00000000000000000000000000000000:1F40 00000000000000000000000000000000:0000 0A 0 0 0 0",
            "   2: 0000000000000000FFFF00000100007F:1435 00000000000000000000000000000000:0000 0A 0 0 0 0",
        ).joinToString("\n")
        assertEquals(
            listOf(Listener(3000, Reach.PHONE_ONLY), Listener(8000, Reach.WIFI), Listener(5173, Reach.PHONE_ONLY)),
            PortScan.parseProcNet(text, v6 = true),
        )
    }

    @Test
    fun malformedLinesAreSkippedNeverThrown() {
        val text = listOf(
            header,
            "",
            "garbage",
            "   0: 0100007F 00000000:0000 0A",
            "   1: 0100007F:ZZZZ 00000000:0000 0A",
            "   2: 0100007F:0000 00000000:0000 0A",
            "   3: 0100007F:10000 00000000:0000 0A",
            "   4: 7F:0BB8 00000000:0000 0A",
            "   5: 01G0007F:0BB8 00000000:0000 0A",
            "   6: 0100007F:0BB8 00000000:0000 0A",
        ).joinToString("\n")
        assertEquals(listOf(Listener(3000, Reach.PHONE_ONLY)), PortScan.parseProcNet(text, v6 = false))
        assertEquals(emptyList<Listener>(), PortScan.parseProcNet("", v6 = false))
        assertNull(PortScan.reachOf("0100007F", v6 = true))
        assertEquals(Reach.PHONE_ONLY, PortScan.reachOf("0100007f", v6 = false))
    }

    @Test
    fun unreadableProcNetMeansUnknownNotEmpty() {
        assertNull(PortScan.readProcNet(temp.newFolder("empty")))
    }

    @Test
    fun aPortOnBothFamiliesTakesTheWiderReach() {
        val root = temp.newFolder("net")
        File(root, "tcp").writeText("$header\n   0: 0100007F:0BB8 00000000:0000 0A 0 0 0 0\n")
        File(root, "tcp6").writeText("$header\n   0: 00000000000000000000000000000000:0BB8 00000000000000000000000000000000:0000 0A 0 0 0 0\n")
        assertEquals(listOf(Listener(3000, Reach.WIFI)), PortScan.readProcNet(root))
    }

    @Test
    fun scanUsesProcNetAndSkipsSystemPorts() = runBlocking {
        val root = temp.newFolder("net2")
        File(root, "tcp").writeText(
            "$header\n   0: 0100007F:0035 00000000:0000 0A 0 0 0 0\n   1: 00000000:1435 00000000:0000 0A 0 0 0 0\n",
        )
        assertEquals(mapOf(5173 to Reach.WIFI), PortScan.scan(listOf(3000), root))
    }

    @Test
    fun scanFallsBackToConnectingWhenProcNetIsHidden() = runBlocking {
        val hidden = temp.newFolder("hidden")
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val closed = ServerSocket(0).use { it.localPort }
            val found = PortScan.scan(listOf(server.localPort, closed), hidden)
            assertEquals(mapOf(server.localPort to Reach.PHONE_ONLY), found)
        }
    }

    @Test
    fun aServerOnAllAddressesIsVisibleOnTheNetwork() {
        val lan = PortScan.lanAddresses()
        assumeTrue("this machine has no network address besides loopback", lan.isNotEmpty())
        ServerSocket().use { everywhere ->
            everywhere.bind(InetSocketAddress(0))
            assertEquals(Reach.WIFI, PortScan.reachFromLan(everywhere.localPort, lan))
        }
        ServerSocket().use { local ->
            local.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            assertEquals(Reach.PHONE_ONLY, PortScan.reachFromLan(local.localPort, lan))
        }
    }

    @Test
    fun theRealProcNetShowsALiveServer() {
        val real = File("/proc/net/tcp")
        assumeTrue("this machine hides /proc/net", real.canRead())
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val listeners = PortScan.readProcNet()
            assertTrue(listeners.orEmpty().contains(Listener(server.localPort, Reach.PHONE_ONLY)))
        }
    }
}
