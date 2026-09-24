package com.pocketide.bridge

/**
 * The only way a WebView reaches a server inside Linux. Every exposed port gets its own
 * listener on 127.0.0.1 (so each is its own web origin), a per-launch token (set as an
 * HttpOnly cookie on the first load), Host rewritten to `localhost:<port>`, WebSocket upgrade
 * passed through, and timeouts. Ports not on the allow-list are refused; other apps on the phone
 * cannot pass the token check.
 */
interface PortBridge {
    /**
     * Allow-lists [port] and returns the URL the WebView loads first (it sets the cookie).
     * [injectHeaders] are added to every forwarded request: a server of ours inside Linux (the
     * terminal) checks a per-launch secret there, so another app that connects to its port
     * directly, without the bridge, is refused.
     */
    fun expose(port: Int, purpose: String, injectHeaders: Map<String, String> = emptyMap()): BridgedPort

    fun revoke(port: Int)

    /** True for URLs a WebView may load inside the app; anything else opens in Chrome. */
    fun isInternal(url: String): Boolean

    val exposed: List<BridgedPort>

    fun shutdown()
}

data class BridgedPort(
    val targetPort: Int,
    val bridgePort: Int,
    val purpose: String,
    /** First URL to load: sets the cookie, then redirects to "/". */
    val entryUrl: String,
    /** The bridged origin, "http://127.0.0.1:<bridgePort>". */
    val origin: String,
)

/**
 * The channel from Linux to the app: one filesystem Unix socket per room, bound only into that
 * room at /run/pocketide/phone.sock, so the room is identified by which socket it used.
 * Requests are JSON lines: {"id": n, "op": "...", "args": {...}}; replies {"id": n, "ok": ..., ...}.
 *
 * Ops: "open_url" (http/https only, shown to the owner), "mcp" (a PocketIDE MCP tool call),
 * "notify". The app never takes a token, key, config or hook from Linux through it.
 */
interface PhoneBridge {
    fun start(agentId: String)

    fun stop(agentId: String)

    /** Registers the handler for one op. */
    fun handle(op: String, handler: suspend (agentId: String, args: kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonElement)
}
