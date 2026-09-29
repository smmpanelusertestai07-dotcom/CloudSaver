package com.pocketide.ide

import android.content.Context
import com.pocketide.core.Http
import com.pocketide.core.await
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerState
import com.pocketide.linux.GuestRoot
import com.pocketide.linux.LinuxCommand
import com.pocketide.linux.LinuxDirs
import com.pocketide.linux.forEachLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** code-server on this phone, as the agent screen needs it. */
sealed interface IdeState {
    data object Off : IdeState

    data class Starting(val step: String) : IdeState

    /** Running on 127.0.0.1:[port]; the page opens with the `code-server-session` cookie [token]. */
    data class On(val port: Int, val token: String) : IdeState {
        fun url(folder: String): String = "http://127.0.0.1:$port/?folder=" + URLEncoder.encode(folder, "UTF-8")
        val cookie: String get() = "$SESSION_COOKIE=$token"
        val origin: String get() = "http://127.0.0.1:$port"
    }

    data class Failed(val why: String, val fix: String) : IdeState

    companion object {
        const val SESSION_COOKIE = "code-server-session"
    }
}

/**
 * Runs code-server (the IDE the agents' own screens live in) and the port forwarder inside Linux,
 * and passes the app's requests to the companion extension. One code-server serves every project:
 * the page's address names the folder.
 *
 * code-server listens on 127.0.0.1, which every app on the phone can reach, so it asks for a
 * password; the page gets it as its session cookie and never shows a sign-in form. The token is
 * random for each start and is written only to a file in the home that only this app can read.
 */
class Ide(
    private val context: Context,
    private val computer: Computer,
    private val dirs: LinuxDirs,
    /** The owner's keys (environment variables) for the agents. */
    private val keys: () -> Map<String, String>,
    /** The agents installed now, as the companion shows them. */
    private val agents: suspend () -> List<AgentScreen>,
    /** Keeps the app running while code-server does (the foreground service), or lets it go. */
    private val keepAlive: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()
    private val mutableState = MutableStateFlow<IdeState>(IdeState.Off)
    val state: StateFlow<IdeState> = mutableState.asStateFlow()

    private var server: Process? = null
    private var forwarder: Process? = null

    /** The last lines code-server printed, for the message when it stops by itself. */
    private val lastLines = ArrayDeque<String>()

    private val home get() = GuestRoot(dirs.home)

    /** Starts code-server when it is not running; returns once its page answers. */
    suspend fun start(): IdeState.On = oneAtATime.withLock {
        (state.value as? IdeState.On)?.takeIf { server?.isAlive == true && forwarder?.isAlive == true }?.let { return it }
        stopProcesses()
        try {
            launch().also { mutableState.value = it }
        } catch (cancelled: CancellationException) {
            stopProcesses()
            mutableState.value = IdeState.Off
            throw cancelled
        } catch (failure: Exception) {
            stopProcesses()
            val failed = failed(failure)
            mutableState.value = failed
            throw IdeUnavailable(failed)
        }
    }

    /** Stops code-server and everything the agents started under it; files and sign-ins stay. */
    suspend fun stop() = oneAtATime.withLock {
        stopProcesses()
        mutableState.value = IdeState.Off
    }

    /** Shows [agentId]'s screen full screen in the open window. */
    fun show(agentId: String) = request(JsonObject(mapOf("do" to JsonPrimitive("show"), "agent" to JsonPrimitive(agentId))))

    /** Opens a terminal titled [title] in [folder] with [text] typed but not run. */
    fun terminal(title: String, text: String, folder: String) = request(
        JsonObject(
            mapOf(
                "do" to JsonPrimitive("terminal"),
                "title" to JsonPrimitive(title),
                "text" to JsonPrimitive(text),
                "cwd" to JsonPrimitive(folder),
            ),
        ),
    )

    private suspend fun launch(): IdeState.On {
        val computerState = computer.state.value
        if (computerState != ComputerState.Ready && computerState !is ComputerState.Updating) {
            throw IdeUnavailable(IdeState.Failed(NOT_SET_UP, FIX_SET_UP))
        }
        mutableState.value = IdeState.Starting("Getting the agents ready…")
        prepare()
        mutableState.value = IdeState.Starting("Starting code-server…")
        val port = freePort()
        val forwarderPort = freePort(avoid = port)
        val token = randomToken()
        writeHome(CONFIG, "hashed-password: \"$token\"\n".toByteArray(), PRIVATE)
        forwarder = computer.start(LinuxCommand(listOf(NODE, FORWARDER, forwarderPort.toString())))
        val started = computer.start(
            LinuxCommand(
                argv = listOf(
                    CODE_SERVER,
                    "--bind-addr", "127.0.0.1:$port",
                    "--auth", "password",
                    "--config", "${LinuxDirs.GUEST_HOME}/$CONFIG",
                    "--disable-telemetry",
                    "--disable-update-check",
                    "--disable-workspace-trust",
                    "--disable-getting-started-override",
                    // A program's own page (Antigravity's) opens at <port>.localhost, through the forwarder.
                    "--proxy-domain", "{{port}}.localhost:$forwarderPort",
                    // A window whose page is gone ends after five minutes instead of three hours.
                    "--reconnection-grace-time", GRACE_SECONDS.toString(),
                    "--user-data-dir", IdeFiles.USER_DATA,
                    "--extensions-dir", IdeFiles.EXTENSIONS,
                    LinuxDirs.GUEST_PROJECTS,
                ),
                env = keys() + mapOf("BROWSER" to IdeFiles.BROWSER),
                workDir = LinuxDirs.GUEST_PROJECTS,
            ),
        )
        server = started
        watch(started)
        awaitHealthy(port, started)
        keepAlive(true)
        return IdeState.On(port, token)
    }

    /** Settings, the agents list, the sign-in sites and the companion: written before every start. */
    private suspend fun prepare() = withContext(Dispatchers.IO) {
        dirs.projects.mkdirs()
        writeHome(MACHINE_SETTINGS, IdeFiles.machineSettings(fontSize()).toByteArray())
        writeHome(AGENTS, IdeFiles.agentsList(agents()).toByteArray())
        val rootfs = GuestRoot(dirs.rootfs)
        rootfs.readText(PRODUCT_JSON, limit = MAX_PRODUCT_JSON)?.let { text ->
            IdeFiles.trustSignInSites(text)?.takeIf { it != text }?.let { rootfs.write(PRODUCT_JSON, it.toByteArray()) }
        }
        installCompanion()
    }

    /** The companion, when this version of it is not installed yet: with code-server's own installer. */
    private suspend fun installCompanion() {
        val files = companionFiles()
        val stamp = IdeFiles.COMPANION_VERSION + ":" + files.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.contentHashCode()}" }
        if (home.readText(COMPANION_STAMP) == stamp) return
        writeHome(COMPANION_VSIX, IdeFiles.companionPackage(files))
        val output = ArrayDeque<String>()
        val code = computer.run(
            LinuxCommand(
                listOf(
                    CODE_SERVER, "--user-data-dir", IdeFiles.USER_DATA, "--extensions-dir", IdeFiles.EXTENSIONS,
                    "--install-extension", "${LinuxDirs.GUEST_HOME}/$COMPANION_VSIX", "--force",
                ),
            ),
        ) { line -> output.addLast(line).also { if (output.size > KEPT_LINES) output.removeFirst() } }
        if (code != 0) throw IOException("code-server could not install PocketIDE's companion: ${output.lastOrNull().orEmpty()}")
        writeHome(COMPANION_STAMP, stamp.toByteArray())
    }

    private fun companionFiles(): Map<String, ByteArray> = context.assets.list(COMPANION_ASSETS).orEmpty()
        .associateWith { name -> context.assets.open("$COMPANION_ASSETS/$name").use { it.readBytes() } }

    private fun watch(process: Process) {
        lastLines.clear()
        scope.launch {
            runCatching { process.inputStream.forEachLine { line -> synchronized(lastLines) { remember(line) } } }
            runInterruptible { process.waitFor() }
            // Stopped by the owner or by a restart: nothing to report.
            if (server === process && state.value is IdeState.On) {
                val said = synchronized(lastLines) { lastLines.lastOrNull { it.isNotBlank() } }
                mutableState.value = IdeState.Failed(
                    "code-server stopped" + (said?.let { ": ${it.take(MAX_SAID)}" } ?: "."),
                    "Tap Open again. If it keeps stopping, repair the computer on the Computer screen.",
                )
                stopProcesses()
            }
        }
    }

    private fun remember(line: String) {
        lastLines.addLast(line)
        if (lastLines.size > KEPT_LINES) lastLines.removeFirst()
    }

    /** code-server's health page answers without a sign-in once it is ready; a phone may take minutes. */
    private suspend fun awaitHealthy(port: Int, process: Process) {
        val client = Http.client.newBuilder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("http://127.0.0.1:$port/healthz").build()
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) throw IOException("code-server stopped while starting")
            val healthy = try {
                client.newCall(request).await().use { it.isSuccessful }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notYet: IOException) {
                false
            }
            if (healthy) return
            delay(POLL_MS)
        }
        throw IOException("code-server did not start within ${START_TIMEOUT_MS / MS_PER_MINUTE} minutes")
    }

    private fun stopProcesses() {
        val wasRunning = server != null || forwarder != null
        server?.let(computer::stop)
        forwarder?.let(computer::stop)
        server = null
        forwarder = null
        if (wasRunning) keepAlive(false)
    }

    /** A request file for the companion: written whole and renamed into place, so it is never read half written. */
    private fun request(body: JsonObject) {
        scope.launch {
            runCatching {
                val folder = dirs.companionRequests
                folder.mkdirs()
                folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > STALE_REQUEST_MS }?.forEach { it.delete() }
                // Named by time first, so the companion takes them in the order they were made.
                val name = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
                home.write("/${LinuxDirs.COMPANION_REQUESTS}/$name.json", body.toString().toByteArray())
            }
        }
    }

    private fun writeHome(path: String, bytes: ByteArray, mode: Int = PLAIN) {
        home.write("/$path", bytes, mode)
    }

    private fun fontSize(): Int = (BASE_FONT * context.resources.configuration.fontScale).toInt().coerceIn(MIN_FONT, MAX_FONT)

    private fun failed(failure: Exception): IdeState.Failed = when (failure) {
        is IdeUnavailable -> failure.state
        is IllegalStateException -> IdeState.Failed(failure.message ?: NOT_SET_UP, FIX_SET_UP)
        else -> IdeState.Failed(
            "code-server did not start: ${failure.message ?: "no reason given"}.",
            "Tap Open again. If it keeps failing, repair the computer on the Computer screen.",
        )
    }

    private fun freePort(avoid: Int = 0): Int {
        repeat(PORT_ATTEMPTS) {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (port != avoid && port >= MIN_PORT) return port
        }
        throw IOException("no free port on this phone")
    }

    private fun randomToken(): String = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CODE_SERVER = "/opt/code-server/bin/code-server"
        const val NODE = "/opt/code-server/lib/node"
        const val FORWARDER = "/opt/pocketide/forwarder.js"
        const val PRODUCT_JSON = "/opt/code-server/lib/vscode/product.json"
        const val MAX_PRODUCT_JSON = 1024L * 1024
        const val COMPANION_ASSETS = "companion"

        /** Paths inside the home (/root). */
        const val CONFIG = "${LinuxDirs.APP_FILES}/code-server.yaml"
        const val MACHINE_SETTINGS = ".local/share/code-server/Machine/settings.json"
        const val AGENTS = "${LinuxDirs.APP_FILES}/agents.json"
        const val COMPANION_VSIX = "${LinuxDirs.APP_FILES}/companion.vsix"
        const val COMPANION_STAMP = "/${LinuxDirs.APP_FILES}/companion.installed"

        const val PLAIN = 0b110_100_100
        const val PRIVATE = 0b110_000_000
        const val GRACE_SECONDS = 300
        const val START_TIMEOUT_MS = 4 * 60_000L
        const val MS_PER_MINUTE = 60_000L
        const val POLL_MS = 700L
        const val STALE_REQUEST_MS = 2 * 60_000L
        const val KEPT_LINES = 20
        const val MAX_SAID = 200
        const val TOKEN_BYTES = 32
        const val PORT_ATTEMPTS = 20
        const val MIN_PORT = 1024
        const val BASE_FONT = 14f
        const val MIN_FONT = 12
        const val MAX_FONT = 20
        const val NOT_SET_UP = "The computer is not set up yet."
        const val FIX_SET_UP = "Set it up on the Computer screen first."
    }
}

/** code-server could not be started; [state] says why, in the owner's words. */
class IdeUnavailable(val state: IdeState.Failed) : IOException(state.why)
