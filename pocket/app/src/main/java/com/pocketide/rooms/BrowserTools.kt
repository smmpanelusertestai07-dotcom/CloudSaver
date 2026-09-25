package com.pocketide.rooms

/**
 * The agents' test browser, shared by every room: Chromium (Chrome for Testing) and two MCP
 * servers, installed on demand into the computer by `browser/install.py`. The paths here are
 * the one source for both the installer's arguments and each agent's MCP registration.
 */
internal object BrowserTools {
    const val SETUP = "${RoomLayout.TOOLS}/browser-setup"
    const val INSTALLER = "$SETUP/install.py"
    const val PREFIX = "${RoomLayout.TOOLS}/browser"
    const val BROWSERS = "${RoomLayout.TOOLS}/browsers"

    /** code-server's own Node (24.x); Ubuntu's Node is too old for Chrome DevTools MCP. */
    const val NODE = "/opt/code-server/lib/node"
    const val CHROME = "$BROWSERS/chromium-1246/chrome-linux-arm64/chrome"

    /** Written by the installer as its last step, so it exists only after a complete install. */
    const val INSTALLED = "$PREFIX/installed.json"

    /** About what a first install downloads (Chromium, the two servers, the Ubuntu libraries). */
    const val DOWNLOAD_BYTES = 290_000_000L

    /**
     * The browser MCP servers. Chrome refuses to run as root, which proot's fake root counts as,
     * so both run it without its sandbox; usage statistics and CrUX lookups stay off.
     */
    fun servers(): Map<String, McpServer> = mapOf(
        "playwright" to McpServer(
            command = NODE,
            args = listOf(
                "$PREFIX/node_modules/@playwright/mcp/cli.js",
                "--browser", "chromium", "--headless", "--no-sandbox", "--isolated",
                "--output-dir", "/tmp/playwright-output",
            ),
            env = mapOf("PLAYWRIGHT_BROWSERS_PATH" to BROWSERS),
        ),
        "chrome-devtools" to McpServer(
            command = NODE,
            args = listOf(
                "$PREFIX/node_modules/chrome-devtools-mcp/build/src/bin/chrome-devtools-mcp.js",
                "--headless", "--isolated", "--executable-path=$CHROME", "--chrome-arg=--no-sandbox",
                "--no-usage-statistics", "--no-performance-crux",
            ),
        ),
    )
}

/** One stdio MCP server as the agents' config files describe it. */
internal data class McpServer(
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
    /** Seconds; used where the agent's config has such a setting (Codex). */
    val startupTimeoutSec: Int = 60,
    val toolTimeoutSec: Int = 120,
)

internal object PocketMcp {
    const val NAME = "pocketide"

    /** A build waits up to ten minutes in the app; the agent must wait a little longer. */
    val SERVER = McpServer(
        command = "python3",
        args = listOf(RoomLayout.MCP_SERVER),
        startupTimeoutSec = 30,
        toolTimeoutSec = 660,
    )
}
