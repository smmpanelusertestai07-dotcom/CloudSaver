package com.pocketide.bridge

import com.pocketide.core.AppDirs
import java.net.URLEncoder

/**
 * The only way a WebView reaches a server inside Linux. Every exposed port gets its own
 * listener on 127.0.0.1 (so each is its own web origin), a per-launch token (set as an
 * HttpOnly cookie on the first load), Host rewritten to `localhost:<port>`, WebSocket upgrade
 * passed through, and timeouts. Ports not on the allow-list are refused; other apps on the phone
 * cannot pass the token check.
 *
 * Every request needs the cookie, and must come from the bridge's own origin: pages on other
 * loopback ports share cookies with it, so a request whose Origin is another port, or that the
 * browser marks as same-site rather than same-origin, is refused. Each connection carries one
 * request (`Connection: close`), so every request is checked on its own. A host of the form
 * `<n>.localhost:<bridgePort>` reaches port n instead, when n is exposed too; that host keeps its
 * own cookie, set through [BridgedPort.entryUrlTo].
 */
interface PortBridge {
    /**
     * Allow-lists [port] and returns the URL the WebView loads first (it sets the cookie).
     * [injectHeaders] are added to every forwarded request: a server of ours inside Linux (the
     * terminal) checks a per-launch secret there, so another app that connects to its port
     * directly, without the bridge, is refused. They replace any header of the same name the
     * page sent; a "Cookie" entry is merged into the page's cookies instead, so a server's
     * session secret can stay out of the WebView's cookie store.
     *
     * Exposing a port that is already exposed with the same headers returns the same bridge;
     * with other headers, the old bridge is closed and a new one (new port, new token) opened.
     */
    fun expose(port: Int, purpose: String, injectHeaders: Map<String, String> = emptyMap()): BridgedPort

    /** Closes the bridge for [port] (the target port) and every connection still using it. */
    fun revoke(port: Int)

    /**
     * True for URLs a WebView may load inside the app; anything else opens in Chrome.
     * Only http on 127.0.0.1, localhost or `<n>.localhost` (n exposed), at a bridge port.
     */
    fun isInternal(url: String): Boolean

    val exposed: List<BridgedPort>

    /**
     * [listener] hears that data went through [bridge] to [port] (its own target, or the dev
     * server a "<n>.localhost" name routes to), at most about twice a minute per port. It runs on
     * a bridge thread, so it must return quickly.
     */
    fun onTraffic(listener: (bridge: BridgedPort, port: Int) -> Unit) = Unit

    /**
     * Servers listening on this phone (the bridge's own ports left out), each with whether
     * other devices on the same Wi-Fi can open it, for Preview's list and its warning.
     * Where Android lets the app read the phone's socket table this is every listening port of
     * the computer; otherwise only those of [candidates] (announced and common dev ports) that answer.
     */
    suspend fun listeners(candidates: Collection<Int> = emptyList()): List<PortListener>

    fun shutdown()
}

/** A server listening on this phone. */
data class PortListener(
    val port: Int,
    /** Bound to every address (0.0.0.0 or ::), so anyone on the same Wi-Fi can open it. */
    val onNetwork: Boolean,
)

data class BridgedPort(
    val targetPort: Int,
    val bridgePort: Int,
    val purpose: String,
    /** First URL to load: sets the cookie, then redirects to "/". */
    val entryUrl: String,
    /** The bridged origin, "http://127.0.0.1:<bridgePort>". */
    val origin: String,
) {
    /**
     * [entryUrl], landing on [path] instead of "/": a path on this origin, query included,
     * already percent-encoded (for example "/?folder=%2Fwork%2Fdemo").
     * With [viaSubPort], the entry is on `<viaSubPort>.localhost:<bridgePort>`, the host that
     * reaches that other exposed port; each host needs its own entry because cookies are per host.
     */
    fun entryUrlTo(path: String, viaSubPort: Int? = null): String {
        require(BridgeAccess.isSafeNext(path)) { "\"$path\" is not a path on this origin." }
        val base = if (viaSubPort == null) origin else "http://$viaSubPort.localhost:$bridgePort"
        return "$base${entryUrl.removePrefix(origin)}&next=${URLEncoder.encode(path, "UTF-8")}"
    }

    /** Never prints [entryUrl]: it carries the token. */
    override fun toString() = "BridgedPort(targetPort=$targetPort, bridgePort=$bridgePort, purpose=$purpose, origin=$origin)"
}

/**
 * The channel from Linux to the app: one filesystem Unix socket per room, bound only into that
 * room at /run/pocketide/phone.sock, so the room is identified by which socket it used.
 * Requests are JSON lines, `{"id":n,"op":"...","args":{...}}`, and each gets one reply line,
 * `{"id":n,"ok":true,"result":...}` or `{"id":n,"ok":false,"error":"..."}`.
 *
 * Ops: "open_url" (http/https only, shown to the owner), "mcp" (a PocketIDE MCP tool call),
 * "notify"; all but "open_url" are registered by the modules that serve them. The app never
 * takes a token, key, config or hook from Linux through it.
 *
 * Limits: a request line of at most 1 MB, 8 connections per room, 16 requests at once per
 * connection, 10 minutes per request (long MCP tools such as a build need it). Requests on one
 * connection run concurrently and replies carry their id, so they may come back in any order.
 *
 * "open_url" is built in: it accepts only http and https addresses without a user name, at most
 * one every 2 seconds per room, and opens them in the phone's browser while PocketIDE is on the
 * screen, where the owner sees the page and its address. It does not ask first: sign-in pages
 * must open without extra taps, and a web page is no more than a link the agent could print.
 * A screen that wants to confirm first registers its own "open_url" with [handle].
 */
interface PhoneBridge {
    /** Opens the room's socket (idempotent). A failure is logged; the room still runs without it. */
    fun start(agentId: String)

    /** Closes the room's socket and ends its connections and running requests. */
    fun stop(agentId: String)

    /**
     * Registers the handler for one op, replacing any earlier one (the built-in "open_url" too).
     * Handlers run concurrently on a supervisor scope. A handler throws IllegalArgumentException
     * or IllegalStateException with a plain sentence for the agent; any other failure is
     * reported without detail.
     */
    fun handle(op: String, handler: suspend (agentId: String, args: kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonElement)

    companion object {
        /** The socket's file name inside the room's bridge directory. */
        const val SOCKET_NAME = "phone.sock"

        /** Where a room sees the socket. */
        const val GUEST_SOCKET = "${AppDirs.GUEST_BRIDGE}/$SOCKET_NAME"

        const val OPEN_URL = "open_url"
    }
}
