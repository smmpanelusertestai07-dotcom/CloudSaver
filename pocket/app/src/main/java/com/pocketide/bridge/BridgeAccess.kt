package com.pocketide.bridge

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where a request is going: the bridge's own target, or another exposed port by host name. */
internal sealed interface Route {
    data object Main : Route
    data class Sub(val port: Int) : Route
}

/**
 * The rules that decide who may use a bridge. Cookies are shared by every port of a host
 * (RFC 6265 §8.5) and SameSite ignores ports, so the port of the page a request comes from
 * is checked separately, through Origin and Sec-Fetch-Site.
 */
internal object BridgeAccess {
    const val ENTRY_PATH = "/_pocketide/enter"
    const val COOKIE_PREFIX = "pide_"
    private const val TOKEN_BYTES = 32
    private const val MAX_NEXT_LENGTH = 4096
    private const val MAX_STALE_COOKIES = 50

    fun newToken(random: SecureRandom): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun cookieName(bridgePort: Int) = "$COOKIE_PREFIX$bridgePort"

    fun sameSecret(given: String, expected: String): Boolean =
        MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    /** Every name=value pair of every Cookie field. */
    fun cookies(headers: HeaderList): List<Pair<String, String>> =
        headers.all("Cookie").flatMap { it.split(';') }.mapNotNull { pair ->
            val trimmed = pair.trim()
            val equals = trimmed.indexOf('=')
            if (equals <= 0) null else trimmed.substring(0, equals).trim() to trimmed.substring(equals + 1).trim()
        }

    fun hasValidCookie(headers: HeaderList, cookieName: String, token: String): Boolean =
        cookies(headers).any { (name, value) -> name == cookieName && sameSecret(value, token) }

    /**
     * Cookies of bridges that no longer exist. Each launch gets a new port and so a new cookie
     * name; clearing the old ones at entry keeps them from piling up on 127.0.0.1.
     */
    fun staleCookies(headers: HeaderList, liveBridgePorts: Set<Int>): List<String> =
        cookies(headers).map { it.first }
            .filter { name ->
                val port = name.removePrefix(COOKIE_PREFIX)
                name.startsWith(COOKIE_PREFIX) && port.isNotEmpty() && port.length <= 5 &&
                    port.all(Char::isDigit) && port.toInt() !in liveBridgePorts
            }
            .distinct()
            .take(MAX_STALE_COOKIES)

    /** The Cookie field for the target: no bridge's cookie ever reaches a server behind it. */
    fun forwardedCookie(headers: HeaderList, injected: String?): String? {
        val injectedNames = injected.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()
        val kept = headers.all("Cookie").flatMap { it.split(';') }.map { it.trim() }.filter { pair ->
            val name = pair.substringBefore('=').trim()
            pair.isNotEmpty() && !name.startsWith(COOKIE_PREFIX) && name !in injectedNames
        }
        val all = if (injected.isNullOrBlank()) kept else kept + injected.trim()
        return all.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** Parses "host[:port]" as sent in Host; null for anything but this bridge's own names. */
    fun route(hostField: String, bridgePort: Int): Route? {
        val host = hostField.trim().lowercase()
        val colon = host.lastIndexOf(':')
        if (colon < 0 || host.substring(colon + 1) != bridgePort.toString()) return null
        return routeForName(host.substring(0, colon))
    }

    /** "127.0.0.1" and "localhost" are the bridge's own target; "<n>.localhost" is port n. */
    fun routeForName(name: String): Route? = when {
        name == "127.0.0.1" || name == "localhost" -> Route.Main
        name.endsWith(".localhost") -> portNumber(name.removeSuffix(".localhost"))?.let(Route::Sub)
        else -> null
    }

    fun portNumber(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || text[0] == '0' || !text.all(Char::isDigit)) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    /**
     * True when the page that sent the request is this very origin. Another loopback port is
     * "same-site" to the browser and would otherwise ride on the cookie (and a WebSocket is not
     * bound by CORS at all), so Origin must match exactly. "null" is accepted only when the
     * browser itself says the request is same-origin (a form posted under no-referrer).
     */
    fun sameOriginOnly(headers: HeaderList, hostField: String): Boolean {
        val fetchSites = headers.all("Sec-Fetch-Site").map { it.trim().lowercase() }
        if ("same-site" in fetchSites) return false
        val origins = headers.all("Origin")
        if (origins.isEmpty()) return true
        if (origins.size > 1) return false
        val origin = origins.single().trim()
        if (origin == "null") return fetchSites == listOf("same-origin")
        return origin.equals("http://${hostField.trim()}", ignoreCase = true)
    }

    /** Query parameters, decoded. Throws IllegalArgumentException for broken %-escapes. */
    fun queryParameters(query: String): Map<String, List<String>> =
        query.split('&').filter { it.isNotEmpty() }.groupBy(
            keySelector = { URLDecoder.decode(it.substringBefore('='), "UTF-8") },
            valueTransform = { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") },
        )

    /**
     * Where the entry may send the browser: a path on this same origin, never "//host" or
     * "/\host" (both leave the origin), and nothing a browser would strip or reinterpret.
     */
    fun isSafeNext(next: String): Boolean =
        next.length in 1..MAX_NEXT_LENGTH &&
            next[0] == '/' &&
            (next.length == 1 || (next[1] != '/' && next[1] != '\\')) &&
            next.all { it in '!'..'~' && it != '\\' }
}
