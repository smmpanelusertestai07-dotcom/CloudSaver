package com.pocketide.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pocketide.core.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A host the app or an agent talks to, and why. */
internal data class Endpoint(val host: String, val purpose: String) {
    val url: String get() = "https://$host/"
}

/** Every host PocketIDE and its agents need, in the order the check shows them. */
internal object NeededHosts {
    val all = listOf(
        Endpoint("github.com", "GitHub: your code"),
        // github.com answers a release download with a redirect to here, which the check does not follow.
        Endpoint(GITHUB_DOWNLOADS, "GitHub downloads (the computer's editor)"),
        Endpoint("api.github.com", "GitHub: projects, builds and sign-in"),
        Endpoint("oauth2.googleapis.com", "Google sign-in"),
        Endpoint("www.googleapis.com", "Google Drive: your AI data"),
        Endpoint("open-vsx.org", "Agent extensions (Open VSX)"),
        Endpoint("openvsx.eclipsecontent.org", "Agent extension downloads"),
        Endpoint("ports.ubuntu.com", "Ubuntu packages"),
        Endpoint("cdimage.ubuntu.com", "Ubuntu for set-up"),
        Endpoint("api.anthropic.com", "Claude"),
        Endpoint("chatgpt.com", "Codex (ChatGPT sign-in)"),
        Endpoint("api.openai.com", "Codex"),
        Endpoint("antigravity-cli-auto-updater-974169037036.us-central1.run.app", "Antigravity updates"),
        Endpoint("storage.googleapis.com", "Antigravity downloads"),
        Endpoint("registry.npmjs.org", "npm packages"),
        Endpoint("cdn.playwright.dev", "The agents' browser"),
    )

    const val GITHUB_DOWNLOADS = "release-assets.githubusercontent.com"

    /** Looked up from inside Linux: Ubuntu's own archive, which every apt run needs. */
    const val LINUX_LOOKUP = "ports.ubuntu.com"
}

/** Asks each host for an answer; any HTTP answer, even an error page, means the host was reached. */
internal class HostProbe(client: OkHttpClient) {
    private val quick = client.newBuilder()
        .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun checkAll(endpoints: List<Endpoint>): List<HostCheck> = coroutineScope {
        endpoints.map { endpoint -> async { check(endpoint) } }.awaitAll()
    }

    suspend fun check(endpoint: Endpoint, url: String = endpoint.url): HostCheck = try {
        quick.newCall(Request.Builder().url(url).head().build()).await().use { response ->
            HostCheck(endpoint.host, endpoint.purpose, ok = true, detail = "Answered (${response.code}).")
        }
    } catch (failure: Exception) {
        HostCheck(endpoint.host, endpoint.purpose, ok = false, detail = explain(failure))
    }

    companion object {
        const val TIMEOUT_S = 10L

        fun explain(failure: Exception): String = when (failure) {
            is UnknownHostException -> "Its name could not be looked up."
            is SSLException -> "Something on this network answered in its place (a sign-in page, a filter or a proxy)."
            is ConnectException -> "The connection was refused."
            is InterruptedIOException -> "No answer within $TIMEOUT_S s."
            else -> "The connection failed."
        }
    }
}

/** What Android says about the network right now. */
internal data class PhoneNetwork(
    val online: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val vpn: Boolean,
    val metered: Boolean,
    /** Private DNS set to a host name ("strict"); automatic mode is not a blocker. */
    val privateDnsServer: String?,
    /** Data Saver is on and the app is not exempt. */
    val dataSaver: Boolean,
) {
    companion object {
        fun read(context: Context): PhoneNetwork {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return PhoneNetwork(false, false, false, false, false, null, false)
            return try {
                val network = manager.activeNetwork
                val capabilities = network?.let(manager::getNetworkCapabilities)
                val links = network?.let(manager::getLinkProperties)
                PhoneNetwork(
                    online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                    validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                    vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                    metered = manager.isActiveNetworkMetered,
                    privateDnsServer = links?.privateDnsServerName?.takeIf { links.isPrivateDnsActive },
                    dataSaver = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                )
            } catch (unavailable: RuntimeException) {
                PhoneNetwork(false, false, false, false, false, null, false)
            }
        }
    }
}

/** Turns what was seen into the blockers the owner can act on, most likely first. */
internal object NetworkBlockers {
    fun describe(phone: PhoneNetwork, hosts: List<HostCheck>, linuxDns: HostCheck?): List<String> = buildList {
        if (!phone.online) {
            add("The phone has no internet connection. Turn on Wi-Fi or mobile data, then check again.")
            return@buildList
        }
        if (phone.captivePortal) {
            add("This Wi-Fi wants you to sign in first. Open Chrome, finish the sign-in page, then check again.")
        } else if (!phone.validated) {
            add("Android says this network does not reach the internet. Try another Wi-Fi or mobile data.")
        }
        val phoneLooksUp = hosts.any { it.ok }
        if (linuxDns != null && !linuxDns.ok && phoneLooksUp) {
            add(
                if (phone.privateDnsServer != null) {
                    "Private DNS (${phone.privateDnsServer}) is on. Linux asks the network's own DNS servers, and this " +
                        "network does not answer them. Set Private DNS to Automatic in Android's settings, or use another network."
                } else {
                    "Linux could not look up names, although the phone can. Check again after the network settles; " +
                        "if it stays, restart the computer."
                },
            )
        }
        if (phone.vpn && hosts.any { !it.ok }) {
            add("A VPN is on. Allow PocketIDE in the VPN app, or turn the VPN off while you check.")
        }
        val intercepted = hosts.filter { !it.ok && it.detail.startsWith("Something on this network") }
        if (intercepted.isNotEmpty() && !phone.captivePortal) {
            add("This network answers for ${intercepted.joinToString { it.host }} itself (a filter or proxy). Use another network for those.")
        }
        if (phone.dataSaver) {
            add("Data Saver is on and PocketIDE is not exempt, so work in the background can stop. Allow unrestricted data for PocketIDE.")
        }
        if (phone.metered) {
            add("You are on mobile data, so big downloads wait for Wi-Fi.")
        }
    }
}
