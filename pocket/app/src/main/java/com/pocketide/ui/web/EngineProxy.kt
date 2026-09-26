package com.pocketide.ui.web

import java.net.URI

/**
 * code-server's own address for a port on this phone, "http://127.0.0.1:<any>/proxy/<n>/<rest>"
 * (or localhost), as VS Code's asExternalUri makes it. The rooms run code-server with
 * --disable-proxy, so that route does not exist, while Chrome reaches the port itself.
 */
object EngineProxy {
    /** "http://localhost:<n>/<rest>" with the query and fragment; any other address comes back as it was. */
    fun unwrap(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?.takeIf { it.scheme.equals("http", ignoreCase = true) && it.rawUserInfo == null } ?: return url
        if (uri.host?.lowercase() !in LOOPBACK_NAMES) return url
        val proxied = ROUTE.matchEntire(uri.rawPath.orEmpty()) ?: return url
        val port = proxied.groupValues[1].toInt().takeIf { it in 1..MAX_PORT } ?: return url
        return buildString {
            append("http://localhost:").append(port).append('/').append(proxied.groupValues[2])
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    private const val MAX_PORT = 65535
    private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost")

    /** "/proxy/<n>" alone or followed by "/<rest>"; n is one to five ASCII digits. */
    private val ROUTE = Regex("/proxy/([0-9]{1,5})(?:/(.*))?", RegexOption.DOT_MATCHES_ALL)
}
