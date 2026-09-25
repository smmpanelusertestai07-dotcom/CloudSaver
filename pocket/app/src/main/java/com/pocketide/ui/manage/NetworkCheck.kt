package com.pocketide.ui.manage

import com.pocketide.core.await
import com.pocketide.ui.components.Tone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A site PocketIDE or an agent needs, and what for. */
data class NeededHost(val host: String, val forWhat: String)

enum class HostOutcome { ANSWERED, NOT_FOUND, TIMED_OUT, REFUSED, INTERCEPTED, FAILED }

data class HostResult(val host: NeededHost, val outcome: HostOutcome, val millis: Long = 0) {
    val ok: Boolean get() = outcome == HostOutcome.ANSWERED
}

/** What Android says about the current network, read just before the check. */
data class NetworkFacts(
    val connected: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val vpn: Boolean,
    /** The Private DNS server name when one is set and in use; null otherwise. */
    val privateDnsServer: String?,
    val dataSaver: Boolean,
)

/**
 * "Check network" on the Computer screen: asks every site PocketIDE needs for an answer and
 * names what stands in the way (no connection, a Wi-Fi sign-in page, Private DNS, a VPN, Data
 * Saver). Any HTTP answer counts, even an error page: the question is only whether the site can
 * be reached. The computer inside the app uses the phone's own connection and DNS.
 */
object NetworkCheck {
    const val TIMEOUT_SECONDS = 8L

    val hosts = listOf(
        NeededHost("github.com", "Your code"),
        NeededHost("api.github.com", "GitHub sign-in, builds and usage"),
        NeededHost("oauth2.googleapis.com", "Google sign-in"),
        NeededHost("www.googleapis.com", "Google Drive"),
        NeededHost("open-vsx.org", "Agents and their updates"),
        NeededHost("openvsx.eclipsecontent.org", "Agent downloads"),
        NeededHost("cdimage.ubuntu.com", "Setting up the computer"),
        NeededHost("ports.ubuntu.com", "Ubuntu updates and tools"),
        NeededHost("api.anthropic.com", "Claude"),
        NeededHost("claude.ai", "Claude sign-in"),
        NeededHost("chatgpt.com", "Codex sign-in"),
        NeededHost("api.openai.com", "Codex"),
        NeededHost("antigravity-cli-auto-updater-974169037036.us-central1.run.app", "Antigravity updates"),
        NeededHost("storage.googleapis.com", "Antigravity downloads"),
        NeededHost("registry.npmjs.org", "Tools agents install (npm)"),
        NeededHost("cdn.playwright.dev", "The agents' browser"),
    )

    /** Asks every host at once; each gets [TIMEOUT_SECONDS]. */
    suspend fun run(
        client: OkHttpClient,
        targets: List<NeededHost> = hosts,
        scheme: String = "https",
        port: Int? = null,
        timeoutMs: Long = TIMEOUT_SECONDS * 1000,
    ): List<HostResult> {
        val quick = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return coroutineScope {
            targets.map { target -> async { probe(quick, target, scheme, port) } }.awaitAll()
        }
    }

    private suspend fun probe(client: OkHttpClient, target: NeededHost, scheme: String, port: Int?): HostResult {
        val url = "$scheme://${target.host}${port?.let { ":$it" }.orEmpty()}/"
        val request = Request.Builder().url(url).head().build()
        val started = System.nanoTime()
        return try {
            client.newCall(request).await().use { }
            HostResult(target, HostOutcome.ANSWERED, (System.nanoTime() - started) / 1_000_000)
        } catch (e: IOException) {
            HostResult(target, outcomeOf(e))
        }
    }

    fun outcomeOf(error: IOException): HostOutcome = when (error) {
        is UnknownHostException -> HostOutcome.NOT_FOUND
        is SSLException -> HostOutcome.INTERCEPTED
        is ConnectException -> HostOutcome.REFUSED
        is InterruptedIOException -> HostOutcome.TIMED_OUT
        is SocketException -> HostOutcome.REFUSED
        else -> HostOutcome.FAILED
    }

    fun describe(result: HostResult): String = when (result.outcome) {
        HostOutcome.ANSWERED -> "answered in ${result.millis} ms"
        HostOutcome.NOT_FOUND -> "name not found"
        HostOutcome.TIMED_OUT -> "no answer in $TIMEOUT_SECONDS s"
        HostOutcome.REFUSED -> "could not connect"
        HostOutcome.INTERCEPTED -> "the secure connection was changed on the way"
        HostOutcome.FAILED -> "did not answer"
    }

    /** What stands in the way, most likely cause first. Empty facts about the network mean "unknown". */
    fun blockers(facts: NetworkFacts, results: List<HostResult>): List<Told> {
        if (!facts.connected) return listOf(Told("No network. Turn on Wi-Fi or mobile data, then check again.", Tone.ERROR))
        val failed = results.filterNot { it.ok }
        val out = mutableListOf<Told>()
        if (facts.captivePortal) {
            out += Told("This Wi-Fi wants you to sign in on its own page first. Open any website in Chrome to see it.", Tone.ERROR)
        } else if (!facts.validated) {
            out += Told("Android says this network has no working internet.", Tone.ERROR)
        }
        if (failed.any { it.outcome == HostOutcome.NOT_FOUND }) {
            out += if (facts.privateDnsServer != null) {
                Told(
                    "Some names were not found through Private DNS (${facts.privateDnsServer}). " +
                        "Try Android Settings → Network → Private DNS → Automatic.",
                    Tone.WARN,
                )
            } else {
                Told("Some names were not found. The network's DNS may block them.", Tone.WARN)
            }
        }
        if (failed.any { it.outcome == HostOutcome.INTERCEPTED }) {
            out += Told("Something on this network changed secure connections: a VPN, a filter app or a Wi-Fi sign-in page.", Tone.ERROR)
        }
        if (facts.vpn && failed.isNotEmpty()) {
            out += Told("A VPN is on. It may block some of these sites; try once without it.", Tone.WARN)
        }
        if (facts.dataSaver) {
            out += Told("Data Saver is on, so Android may stop PocketIDE's transfers in the background. Allow PocketIDE unrestricted data.", Tone.WARN)
        }
        if (failed.isEmpty() && out.none { it.tone == Tone.ERROR }) {
            out.add(0, Told("Every site PocketIDE needs answered.", Tone.OK))
        } else if (failed.isNotEmpty() && out.isEmpty()) {
            out += Told(
                "${ManageFormat.count(failed.size, "site")} did not answer. It may be down for a moment, or blocked on this network.",
                Tone.WARN,
            )
        }
        return out
    }
}
