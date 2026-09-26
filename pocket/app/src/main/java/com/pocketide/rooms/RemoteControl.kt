package com.pocketide.rooms

/**
 * Antigravity through Google's own Remote Control (antigravity.google/docs/remote-control):
 * `agy remote-control start` registers the room's agy CLI as an always-on instance, and the owner
 * drives it from Google's Remote Control page in any browser, signed in with the same Google
 * Account (the page can go on the home screen, with notifications). PocketIDE exposes no port of
 * it through its bridge, so the in-app screen's hub guard is not involved.
 *
 * Any loopback port the daemon opens is reachable by every app on the phone, so each one must
 * turn away a caller that has no key, as the hub must; otherwise Remote Control is stopped again.
 * Not yet tried on a phone: the daemon registers as a systemd user service where one exists.
 */
internal object RemoteControl {
    /** Google's Remote Control page, as its documentation links it. */
    const val DASHBOARD = "https://antigravity.google.com"

    /** How the instance is named on Google's page. */
    const val INSTANCE_NAME = "PocketIDE phone"

    /** Time the daemon gets to open its ports before they are checked. */
    const val SETTLE_MS = 5_000L

    const val ONLY_ANTIGRAVITY = "Remote Control is Antigravity's own. Open it from the Antigravity room."

    /**
     * Runs the room's agy with `remote-control start`. The instance name is a positional
     * argument ($1), never shell code.
     */
    fun startCommand(name: String = INSTANCE_NAME): List<String> = listOf("/bin/sh", "-c", SCRIPT, "pocketide-remote-control", name)

    internal val SCRIPT = """
        set -eu
        bin="${'$'}HOME/.gemini/bin/agy"
        [ -x "${'$'}bin" ] || { echo "Antigravity is not installed in this room yet. Open it once, then try again." >&2; exit 127; }
        exec "${'$'}bin" remote-control start --name "${'$'}1"
    """.trimIndent()

    /**
     * Why Remote Control may not stay on, or null. [answers] holds each loopback port the daemon
     * opened with its answer to a request that has no key (null: it did not answer like a web
     * server). Only a refusal (4xx) counts as keeping other apps out, as for the hub.
     */
    fun problem(answers: Map<Int, HttpAnswer?>): String? = when {
        answers.values.any { it != null && it.status !in HTTP_CLIENT_ERRORS } -> OPEN_TO_OTHER_APPS
        answers.values.any { it == null } -> CANNOT_CHECK
        else -> null
    }

    /** Why the daemon did not start, with its last words. */
    fun notStarted(lastWords: List<String>): String =
        "Remote Control did not start." + lastWords.takeIf { it.isNotEmpty() }?.let { " Antigravity said: ${it.joinToString(" / ")}" }.orEmpty()

    const val OPEN_TO_OTHER_APPS = "Remote Control stays off: this version of Antigravity opened a port on this phone that " +
        "answers any app without asking for its key, so another app could use Antigravity in your projects. " +
        "It can be turned on once an update of Antigravity fixes this."

    const val CANNOT_CHECK = "Remote Control stays off: this version of Antigravity opened a port on this phone that " +
        "PocketIDE cannot check, so it cannot tell whether other apps are kept out."

    private val HTTP_CLIENT_ERRORS = 400..499
}
