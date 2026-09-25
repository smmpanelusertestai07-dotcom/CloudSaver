package com.pocketide.rooms

import com.pocketide.core.AppDirs
import com.pocketide.linux.LinuxCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * The command lines of a room's programs, with exactly the room's binds and environment.
 *
 * A launch's secret never goes in arguments or in the environment: every program of this app,
 * those of the other rooms included, can read another's /proc/<pid>/cmdline and environ. It goes
 * in a file in the room's bridge folder (written owner-only by the app just before the start),
 * which the program reads at start-up and which is deleted right after.
 */
internal object RoomEngines {
    const val CODE_SERVER = "/opt/code-server/bin/code-server"
    const val AGY = "${AppDirs.GUEST_HOME}/.gemini/bin/agy"
    const val SESSION_COOKIE = "code-server-session"
    private const val USER_DATA = "${AppDirs.GUEST_HOME}/${RoomConfigurator.USER_DATA}"

    /** The launch-secret file for [port], relative to the room's bridge folder. */
    fun secretFile(kind: String, port: Int) = ".$kind-$port.secret"

    fun guestSecretFile(kind: String, port: Int) = "${AppDirs.GUEST_BRIDGE}/${secretFile(kind, port)}"

    /**
     * The config file code-server reads for a launch: only the SHA-256 of this launch's random
     * secret, as its hashed password. With a SHA-256 hashed password, code-server 4.138 accepts a
     * request whose `code-server-session` cookie equals that hash (out/node/util.js, isCookieValid),
     * so the port bridge adds that cookie to the WebView's requests: the owner never sees a login
     * page, and the WebView's own cookie store never holds it. No other setting is read from a
     * file: whatever else the room might write there is ignored.
     */
    fun codeServerConfig(secret: String) = "hashed-password: \"${sha256Hex(secret)}\"\n"

    /** code-server on [port], opened on the session's worktree, through room.py. */
    fun codeServer(dirs: AppDirs, profile: RoomProfile, guestWorktree: String, port: Int, environment: Map<String, String>): LinuxCommand =
        LinuxCommand(
            argv = launcher(profile.agentId) + listOf(
                CODE_SERVER,
                "--bind-addr", "127.0.0.1:$port",
                "--auth", "password",
                "--config", guestSecretFile(CODE_SERVER_KIND, port),
                "--disable-telemetry",
                "--disable-update-check",
                "--disable-workspace-trust",
                "--disable-getting-started-override",
                // No /proxy/<port> route: the bridge's allow-list decides which ports a WebView reaches.
                "--disable-proxy",
                "--user-data-dir", USER_DATA,
                "--extensions-dir", "$USER_DATA/extensions",
                guestWorktree,
            ),
            binds = RoomLayout.binds(dirs, profile.agentId),
            env = environment + mapOf(
                // A sign-in callback on localhost must stay on localhost: the phone's browser reaches it
                // directly, while code-server's default /proxy/<port>/ address would need the bridge.
                "VSCODE_PROXY_URI" to "http://localhost:{{port}}/",
                "POCKETIDE_OPEN_COMMAND" to profile.openCommand.orEmpty(),
                "POCKETIDE_OPEN_PLACE" to profile.place.word,
                "POCKETIDE_VIEW_TYPES" to profile.viewTypes.joinToString(","),
                "POCKETIDE_PROMPT_COMMAND" to profile.promptCommand.orEmpty(),
                "POCKETIDE_PROMPT_DIR" to AppDirs.GUEST_BRIDGE,
            ),
            workDir = guestWorktree,
        )

    /** The cookie the port bridge adds to every request to a code-server room. */
    fun sessionCookie(secret: String): String = "$SESSION_COOKIE=${sha256Hex(secret)}"

    /**
     * A first prompt for the room's agent, relative to the room's bridge folder: the companion
     * picks it up, deletes it and opens the agent with it (in its composer, not sent).
     */
    fun promptFile(id: String) = ".prompt-$id.json"

    fun promptRequest(prompt: String): String =
        Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("prompt" to JsonPrimitive(prompt))))

    /**
     * Antigravity's hub (agy) on [port] for the session's worktree, as its VS Code extension starts
     * it, with agy's own self-updater off (PocketIDE installs and checks agy). The extension also
     * passes a CSRF token, but agy takes it only as an argument, where every room could read it:
     * none is given, and the WebView reaches the hub only through the bridge's per-launch cookie.
     */
    fun hub(dirs: AppDirs, profile: RoomProfile, guestWorktree: String, port: Int, environment: Map<String, String>): LinuxCommand =
        LinuxCommand(
            argv = launcher(profile.agentId) + listOf(
                AGY,
                "--hub",
                "--hub-port=$port",
                "--app_data_dir=antigravity",
                "--add-dir=$guestWorktree",
            ),
            binds = RoomLayout.binds(dirs, profile.agentId),
            env = environment + mapOf(
                "AGY_ENABLE_HUB" to "1",
                "ANTIGRAVITY_VSCODE_HOST" to "1",
                "ANTIGRAVITY_AUTH_SUCCESS_APP" to "vscode",
                "AGY_CLI_DISABLE_AUTO_UPDATE" to "true",
            ),
            workDir = guestWorktree,
        )

    /** The `>_` terminal server on [port], in the room of [agentId], starting in the session's worktree. */
    fun terminal(dirs: AppDirs, agentId: String, guestWorktree: String, port: Int, environment: Map<String, String>): LinuxCommand =
        LinuxCommand(
            argv = listOf(
                RoomLayout.PYTHON, RoomLayout.TERMINAL_SERVER,
                "--port", port.toString(),
                "--cwd", guestWorktree,
                "--web", RoomLayout.TERMINAL_WEB,
                "--secret-file", guestSecretFile(TERMINAL_KIND, port),
            ),
            binds = RoomLayout.binds(dirs, agentId),
            env = environment,
            workDir = guestWorktree,
        )

    /**
     * A program run to its end in the room of [agentId] (a scheduled task), through room.py like
     * the engines, so what it writes is private to the room as well.
     */
    fun headless(dirs: AppDirs, agentId: String, argv: List<String>, workDir: String, environment: Map<String, String>): LinuxCommand =
        LinuxCommand(
            argv = launcher(agentId) + argv,
            binds = RoomLayout.binds(dirs, agentId),
            env = environment,
            workDir = workDir,
        )

    /** Runs room.py's set-up steps in the room and nothing else (a registration without a start). */
    fun setUpOnly(dirs: AppDirs, agentId: String, environment: Map<String, String>): LinuxCommand = LinuxCommand(
        argv = launcher(agentId) + "/bin/true",
        binds = RoomLayout.binds(dirs, agentId),
        env = environment,
        workDir = AppDirs.GUEST_HOME,
    )

    /**
     * True when the program on the port is the room's engine and ready: code-server's health
     * endpoint (no sign-in needed) reports its status; the hub answers its page.
     */
    fun ready(engine: Engine, answer: HttpAnswer?): Boolean = when {
        answer == null -> false
        engine == Engine.CODE_SERVER -> answer.status == 200 && answer.body.contains("\"status\"")
        else -> answer.status in 200..499
    }

    fun readyPath(engine: Engine) = if (engine == Engine.CODE_SERVER) "/healthz" else "/"

    private fun launcher(agentId: String) = listOf(RoomLayout.PYTHON, RoomLayout.ROOM_LAUNCHER, agentId, "--")

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    const val CODE_SERVER_KIND = "code-server"
    const val TERMINAL_KIND = "terminal"
}
