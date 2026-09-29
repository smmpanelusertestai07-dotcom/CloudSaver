package com.pocketide.ui.web

import java.net.URI
import java.util.Locale

/** Where a page in the agent screen wants to go, and what the app does about it. */
enum class Navigation {
    /** The phone's own code-server: stays in the app. */
    STAY,

    /** Any other web address, sign-ins to Google, Anthropic and OpenAI among them: Chrome. */
    CHROME,

    /** Not a web address (intent:, file:, javascript:), or plain http to another computer: goes nowhere. */
    BLOCK,
}

/**
 * URL rules for the agent screen. Pure Kotlin (java.net.URI), so they are tested on the JVM; the
 * WebView's clients call them.
 *
 * Only code-server itself stays inside the app. Google refuses sign-in inside embedded web views,
 * and a browser the owner already trusts is the right place for every company's sign-in page, so
 * everything else opens in Chrome (a Custom Tab), with its own cookies and password manager. Plain
 * http leaves the app only for this phone's own addresses: a sign-in's return page on localhost,
 * or a web app an agent is running.
 */
object WebPolicy {
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    /** [idePort] is the port code-server listens on, or null when it is not running. */
    fun navigation(url: String, idePort: Int?): Navigation {
        val uri = parse(url) ?: return Navigation.BLOCK
        return when {
            idePort != null && isIdePage(uri, idePort) -> Navigation.STAY
            isOpenable(uri) -> Navigation.CHROME
            else -> Navigation.BLOCK
        }
    }

    /** An address Chrome may be given: https anywhere, or http to this phone. */
    fun isWebLink(url: String): Boolean = parse(url)?.let(::isOpenable) == true

    /** The host an "open in Chrome" note names, so the owner sees where it goes. */
    fun hostOf(url: String): String? = parse(url)?.host?.lowercase(Locale.ROOT)

    /**
     * A sign-in page of a company whose agent runs here, from [sites]: https addresses of a host,
     * where a host starting with "*." stands for its subdomains. It opens in Chrome without asking
     * first, as code-server itself opens these sites.
     */
    fun isSignInSite(url: String, sites: List<String>): Boolean {
        val uri = parse(url)?.takeIf { it.scheme.equals("https", ignoreCase = true) } ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return sites.map { it.removePrefix("https://").lowercase(Locale.ROOT) }.any { site ->
            if (site.startsWith("*.")) host.endsWith(site.substring(1)) else host == site
        }
    }

    /** code-server's own page on this phone: the only page the agent screen starts on. */
    fun isIdePage(url: String, idePort: Int): Boolean = parse(url)?.let { isIdePage(it, idePort) } == true

    private fun isIdePage(uri: URI, idePort: Int): Boolean =
        uri.scheme.equals("http", ignoreCase = true) && uri.host == "127.0.0.1" && uri.port == idePort

    private fun isOpenable(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> host in LOOPBACK || host.endsWith(".localhost")
            else -> false
        }
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }
}
