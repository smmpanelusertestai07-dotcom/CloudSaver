package com.pocketide.linux

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkDoctorTest {
    private val wifi = PhoneNetwork(
        online = true,
        validated = true,
        captivePortal = false,
        vpn = false,
        metered = false,
        privateDnsServer = null,
        dataSaver = false,
    )
    private val reached = HostCheck("github.com", "GitHub", ok = true, detail = "Answered (200).")
    private val linuxFails = HostCheck("ports.ubuntu.com", "Looking up names inside Linux", ok = false, detail = "Linux could not look up ports.ubuntu.com.")

    @Test
    fun everyPinnedDownloadAndTheHostItRedirectsToIsChecked() {
        val checked = NeededHosts.all.map { it.host }.toSet()
        for (url in listOf(LinuxPins.ubuntuBase.url, LinuxPins.codeServer.url)) {
            val host = URI(url).host
            assertTrue("$host is checked", host in checked)
            // A GitHub release download answers 302 to GitHub's asset host.
            if (host == "github.com" && "/releases/download/" in url) {
                assertTrue("${NeededHosts.GITHUB_DOWNLOADS} is checked", NeededHosts.GITHUB_DOWNLOADS in checked)
            }
        }
        assertEquals(checked.size, NeededHosts.all.size)
    }

    @Test
    fun anyHttpAnswerMeansTheHostWasReached() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).build())
            val check = runBlocking {
                HostProbe(OkHttpClient()).check(Endpoint("example.test", "Test"), server.url("/").toString())
            }
            assertTrue(check.ok)
            assertEquals("Answered (404).", check.detail)
            assertEquals("HEAD", server.takeRequest().method)
        }
    }

    @Test
    fun aHostThatCannotBeReachedSaysWhy() {
        val check = runBlocking { HostProbe(OkHttpClient()).check(Endpoint("nothing.invalid", "Test"), "http://127.0.0.1:1/") }
        assertFalse(check.ok)
        assertEquals("The connection was refused.", check.detail)
    }

    @Test
    fun failuresInPlainWords() {
        assertEquals("Its name could not be looked up.", HostProbe.explain(UnknownHostException("x")))
        assertTrue(HostProbe.explain(SSLHandshakeException("x")).startsWith("Something on this network answered in its place"))
        assertEquals("The connection was refused.", HostProbe.explain(ConnectException("x")))
        assertEquals("No answer within 10 s.", HostProbe.explain(SocketTimeoutException("x")))
    }

    @Test
    fun theHostListCoversEveryServiceOnce() {
        val hosts = NeededHosts.all.map { it.host }
        assertEquals(hosts.distinct(), hosts)
        for (needed in listOf("github.com", "api.github.com", "open-vsx.org", "openvsx.eclipsecontent.org", "ports.ubuntu.com", "api.anthropic.com", "registry.npmjs.org", "cdn.playwright.dev")) {
            assertTrue(needed, needed in hosts)
        }
        assertTrue(NeededHosts.all.all { it.url == "https://${it.host}/" })
    }

    @Test
    fun nothingToReportOnAGoodWifi() {
        assertEquals(emptyList<String>(), NetworkBlockers.describe(wifi, listOf(reached), reached))
    }

    @Test
    fun offlineSaysOnlyThat() {
        val blockers = NetworkBlockers.describe(wifi.copy(online = false, dataSaver = true), emptyList(), null)
        assertEquals(1, blockers.size)
        assertTrue(blockers.single().startsWith("The phone has no internet connection."))
    }

    @Test
    fun namesPrivateDnsWhenLinuxCannotLookUpNamesButThePhoneCan() {
        val blockers = NetworkBlockers.describe(wifi.copy(privateDnsServer = "dns.adguard.com"), listOf(reached), linuxFails)
        assertEquals(1, blockers.size)
        assertTrue(blockers.single().startsWith("Private DNS (dns.adguard.com) is on."))
        assertTrue(blockers.single().contains("Automatic"))
    }

    @Test
    fun namesTheSignInPageTheVpnAndDataSaver() {
        val failing = HostCheck("api.anthropic.com", "Claude", ok = false, detail = "No answer within 10 s.")
        val blockers = NetworkBlockers.describe(
            wifi.copy(captivePortal = true, validated = false, vpn = true, dataSaver = true, metered = true),
            listOf(reached, failing),
            null,
        )
        assertTrue(blockers[0].startsWith("This Wi-Fi wants you to sign in first."))
        assertTrue(blockers.any { it.startsWith("A VPN is on.") })
        assertTrue(blockers.any { it.startsWith("Data Saver is on") })
        assertTrue(blockers.any { it.startsWith("You are on mobile data") })
    }

    @Test
    fun namesAFilterThatAnswersForSomeHosts() {
        val filtered = HostCheck("chatgpt.com", "Codex", ok = false, detail = HostProbe.explain(SSLHandshakeException("x")))
        val blockers = NetworkBlockers.describe(wifi, listOf(reached, filtered), reached)
        assertEquals(listOf("This network answers for chatgpt.com itself (a filter or proxy). Use another network for those."), blockers)
    }
}
