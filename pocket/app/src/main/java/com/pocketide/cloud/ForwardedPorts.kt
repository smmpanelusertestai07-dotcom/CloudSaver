package com.pocketide.cloud

/**
 * Addresses of a cloud computer's forwarded ports. GitHub keeps them private: only the owner's own
 * GitHub sign-in opens them, so the desktop's fixed password (desktop-lite's "vscode") guards
 * nothing that GitHub does not guard already.
 */
object ForwardedPorts {
    private const val DOMAIN = "app.github.dev"
    private val PORTS = 1..65_535

    /** The page a program serves on [port] inside the computer named [computer]. */
    fun url(computer: String, port: Int): String {
        require(port in PORTS) { "No such port: $port" }
        return "https://$computer-$port.$DOMAIN/"
    }

    /** The computer's desktop, joined at once and scaled to the phone's screen. */
    fun desktop(computer: String): String =
        url(computer, ComputerConfig.DESKTOP_PORT) + "vnc.html?autoconnect=true&resize=scale&reconnect=true&password=vscode"

    /** The port the owner typed, or null when it is not a port number. */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in PORTS }
}
