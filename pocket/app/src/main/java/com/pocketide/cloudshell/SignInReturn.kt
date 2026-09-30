package com.pocketide.cloudshell

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * A sign-in page's return, sent on to Cloud Shell. An agent that signs in with a browser waits in
 * Cloud Shell for the page to return to http://localhost:PORT/...; on a phone that return lands on
 * the phone, where nothing waits ("localhost refused to connect"). The script's bridge hands the
 * same address to the agent in Cloud Shell, as localhost.
 */
object SignInReturn {
    /** The script's bridge takes sign-in returns on this port. */
    const val PORT = 8090
    private const val LOWEST_PORT = 1024
    private const val HIGHEST_PORT = 65535
    private val LOOPBACK = setOf("localhost", "127.0.0.1")

    /** Where a sign-in page that ended at [address] goes instead, in [account]'s Cloud Shell; null when it is no such return. */
    fun address(address: String, account: String): String? {
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return null
        val port = loopbackPort(uri) ?: return null
        val path = (uri.rawPath?.takeIf { it.startsWith("/") } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
        return CloudShell.webPreview(PORT, "/pocketide/callback/$port/$token", account)
    }

    /**
     * The phone port a sign-in page returns to: [page] is an https sign-in page whose redirect_uri
     * is http://localhost:PORT/... (as Antigravity's is). Null for anything else.
     */
    fun pagePort(page: String): Int? {
        val uri = runCatching { URI(page.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        val redirect = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("redirect_uri=") }
            ?.let { runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrNull() }
        return redirect?.let { runCatching { URI(it) }.getOrNull() }?.let(::loopbackPort)
    }

    /** True for a port an agent's sign-in can wait on: not the bridge's own, not a system one. */
    fun isAgentPort(port: Int): Boolean = port in LOWEST_PORT..HIGHEST_PORT && port != PORT

    private fun loopbackPort(uri: URI): Int? =
        uri.port.takeIf { uri.scheme == "http" && uri.host in LOOPBACK && isAgentPort(it) }
}
