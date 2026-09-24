package com.pocketide.ui.manage

import com.pocketide.ui.components.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

class NetworkCheckTest {
    private val closeLater = mutableListOf<AutoCloseable>()

    @After
    fun close() = closeLater.forEach { runCatching { it.close() } }

    /** A tiny HTTP server that answers every request with [status] and no body; plain HTTP, never TLS. */
    private fun server(status: Int, readRequest: Boolean = true): Int {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closeLater += socket
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        val reader = it.getInputStream().bufferedReader()
                        if (readRequest) while (reader.readLine()?.isNotEmpty() == true) Unit
                        it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return socket.localPort
    }

    private val loopback = NeededHost("127.0.0.1", "test")
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

    @Test
    fun `any HTTP answer counts, even an error page`() = runBlocking {
        for (status in listOf(200, 301, 404, 503)) {
            val port = server(status)
            val result = NetworkCheck.run(client, listOf(loopback), scheme = "http", port = port).single()
            assertEquals("status $status", HostOutcome.ANSWERED, result.outcome)
        }
    }

    @Test
    fun `a closed port is refused`() = runBlocking {
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val result = NetworkCheck.run(client, listOf(loopback), scheme = "http", port = port).single()
        assertEquals(HostOutcome.REFUSED, result.outcome)
    }

    @Test
    fun `a server that never answers times out within the limit`() = runBlocking {
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closeLater += silent
        val held = CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
        val result = withTimeout(5_000) {
            NetworkCheck.run(client, listOf(loopback), scheme = "http", port = silent.localPort, timeoutMs = 400).single()
        }
        assertEquals(HostOutcome.TIMED_OUT, result.outcome)
        held.forEach { runCatching { it.close() } }
    }

    @Test
    fun `leaving the screen cancels every probe at once`() = runBlocking {
        val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closeLater += silent
        thread(isDaemon = true) { runCatching { while (true) silent.accept() } }
        val targets = List(8) { NeededHost("127.0.0.1", "test $it") }
        val job = launch(Dispatchers.IO) {
            NetworkCheck.run(client, targets, scheme = "http", port = silent.localPort, timeoutMs = 60_000)
        }
        delay(200)
        val started = System.nanoTime()
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `TLS to something that is not TLS reads as a changed connection`() = runBlocking {
        val port = server(200, readRequest = false)
        val result = NetworkCheck.run(client, listOf(loopback), scheme = "https", port = port, timeoutMs = 3_000).single()
        assertEquals(HostOutcome.INTERCEPTED, result.outcome)
    }

    @Test
    fun `a name DNS cannot find is reported as not found`() = runBlocking {
        val noDns = client.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        }).build()
        val results = NetworkCheck.run(noDns, NetworkCheck.hosts)
        assertEquals(NetworkCheck.hosts.size, results.size)
        assertTrue(results.all { it.outcome == HostOutcome.NOT_FOUND })
    }

    @Test
    fun `exceptions map to plain outcomes`() {
        assertEquals(HostOutcome.NOT_FOUND, NetworkCheck.outcomeOf(UnknownHostException("x")))
        assertEquals(HostOutcome.TIMED_OUT, NetworkCheck.outcomeOf(SocketTimeoutException("x")))
        assertEquals(HostOutcome.INTERCEPTED, NetworkCheck.outcomeOf(SSLHandshakeException("x")))
        assertEquals(HostOutcome.FAILED, NetworkCheck.outcomeOf(IOException("x")))
    }

    @Test
    fun `the host list is https-ready, unique and names each purpose`() {
        val names = NetworkCheck.hosts.map { it.host }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("[a-z0-9.-]+").matches(it) && '.' in it })
        assertTrue(NetworkCheck.hosts.all { it.forWhat.isNotBlank() })
        for (needed in listOf("github.com", "api.github.com", "www.googleapis.com", "open-vsx.org", "registry.npmjs.org")) {
            assertTrue(needed, needed in names)
        }
    }

    private val good = NetworkFacts(connected = true, validated = true, captivePortal = false, vpn = false, privateDnsServer = null, dataSaver = false)
    private fun result(outcome: HostOutcome) = HostResult(loopback, outcome)

    @Test
    fun `all clear says so`() {
        val told = NetworkCheck.blockers(good, listOf(result(HostOutcome.ANSWERED)))
        assertEquals(listOf(Tone.OK), told.map { it.tone })
    }

    @Test
    fun `no network is the only thing said when offline`() {
        val told = NetworkCheck.blockers(good.copy(connected = false, vpn = true, dataSaver = true), listOf(result(HostOutcome.NOT_FOUND)))
        assertEquals(1, told.size)
        assertTrue(told.single().text.startsWith("No network"))
    }

    @Test
    fun `a captive portal is named before the failures it causes`() {
        val told = NetworkCheck.blockers(good.copy(validated = false, captivePortal = true), listOf(result(HostOutcome.INTERCEPTED)))
        assertTrue(told.first().text.contains("sign in on its own page"))
        assertTrue(told.none { it.tone == Tone.OK })
    }

    @Test
    fun `private DNS is blamed for names not found only when it is on`() {
        val withDns = NetworkCheck.blockers(good.copy(privateDnsServer = "dns.example"), listOf(result(HostOutcome.NOT_FOUND)))
        assertTrue(withDns.any { it.text.contains("Private DNS (dns.example)") })
        val without = NetworkCheck.blockers(good, listOf(result(HostOutcome.NOT_FOUND)))
        assertTrue(without.none { it.text.contains("Private DNS") })
    }

    @Test
    fun `a VPN is mentioned only when something failed, Data Saver always`() {
        assertTrue(NetworkCheck.blockers(good.copy(vpn = true), listOf(result(HostOutcome.ANSWERED))).none { it.text.contains("VPN") })
        assertTrue(NetworkCheck.blockers(good.copy(vpn = true), listOf(result(HostOutcome.TIMED_OUT))).any { it.text.contains("VPN") })
        val saver = NetworkCheck.blockers(good.copy(dataSaver = true), listOf(result(HostOutcome.ANSWERED)))
        assertEquals(Tone.OK, saver.first().tone)
        assertTrue(saver.any { it.text.contains("Data Saver") })
    }

    @Test
    fun `an unexplained failure still says how many sites did not answer`() {
        val told = NetworkCheck.blockers(good, listOf(result(HostOutcome.TIMED_OUT), result(HostOutcome.REFUSED), result(HostOutcome.ANSWERED)))
        assertEquals(1, told.size)
        assertTrue(told.single().text.startsWith("2 sites did not answer"))
    }
}
