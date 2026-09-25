package com.pocketide.rooms

import com.pocketide.bridge.BridgedPort
import com.pocketide.bridge.PhoneBridge
import com.pocketide.bridge.PortBridge
import com.pocketide.builds.BuildTemplate
import com.pocketide.core.AppDirs
import com.pocketide.github.PullRequest
import com.pocketide.github.WorkflowRun
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerInfo
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.media.MediaItem
import com.pocketide.model.AgentInfo
import com.pocketide.model.Decision
import com.pocketide.model.Guard
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.PutOnMainResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The room manager against a stand-in computer that runs the engines as local programs: a small
 * Python server answers for code-server and the hub, and the real term.py serves the terminal.
 */
class RoomManagerTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private lateinit var dirs: AppDirs
    private lateinit var env: FakeEnv
    private lateinit var rooms: RoomManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val session = SessionRecord(
        id = "s1", agentId = "claude", projectId = "octo/app", title = "Login", branch = "pocket/claude/2026-09-24-login",
        startedAt = 0, lastActivityAt = 0, deviceId = "phone",
    )

    @Before fun setUp() {
        assumeTrue("python3 runs the stand-in engines", python() != null)
        dirs = AppDirs(temp.newFolder("files"), temp.newFolder("cache"))
        File(dirs.rootfs, "opt/code-server/bin").mkdirs()
        File(dirs.rootfs, "opt/code-server/bin/code-server").writeText("#!/bin/sh\n")
        File(dirs.roomHome("claude"), "${RoomConfigurator.EXTENSIONS}/anthropic.claude-code-2.1.281-linux-arm64").mkdirs()
        dirs.worktree("claude", "octo/app", "s1").mkdirs()
        dirs.worktree("claude", "octo/app", "s2").mkdirs()
        env = FakeEnv(dirs, scope, listOf(session, session.copy(id = "s2"), session.copy(id = "a1", agentId = "antigravity")))
        rooms = RoomManager(env)
    }

    @After fun tearDown() {
        if (::rooms.isInitialized) runBlocking { rooms.stopAll() }
        scope.cancel()
    }

    @Test fun `a code-server room opens on the session's folder, signed in through the bridge`() = runBlocking {
        val state = rooms.open("claude", "s1")
        assertTrue(state.toString(), state is RoomState.Running)
        state as RoomState.Running
        assertEquals("s1", state.sessionId)
        assertTrue(state.url, state.url.contains("next=%2F%3Ffolder%3D%252Fwork%252Focto__app%252Fs1"))
        assertEquals(state, rooms.states.value["claude"])

        val exposed = env.ports.exposed.single()
        assertEquals("agent:claude", exposed.purpose)
        val config = env.computer.configs.single()
        val hash = Regex("hashed-password: \"([0-9a-f]{64})\"").find(config)!!.groupValues[1]
        assertEquals(mapOf("Cookie" to "code-server-session=$hash"), env.ports.injected[exposed.targetPort])
        assertTrue("the password file is gone once code-server has started", dirs.roomBridge("claude").listFiles().orEmpty().none { it.name.endsWith(".secret") })

        val command = env.computer.commands.single()
        assertEquals(RoomLayout.binds(dirs, "claude"), command.binds)
        assertTrue(command.env.keys.none { Regex("(?i)token|secret|passw").containsMatchIn(it) })
        assertTrue(env.phone.started.contains("claude"))
        assertTrue(File(dirs.roomHome("claude"), ".claude/CLAUDE.md").readText().contains(ManagedBlock.BEGIN))
        assertTrue(File(dirs.rootfs, "opt/pocketide/mcp.py").isFile)
    }

    @Test fun `another session of the same project shows in the same engine`() = runBlocking {
        rooms.open("claude", "s1")
        val second = rooms.open("claude", "s2") as RoomState.Running
        assertEquals("s2", second.sessionId)
        assertTrue(second.url.contains("s2"))
        assertEquals(1, env.computer.commands.size)
    }

    @Test fun `stop ends the engine exactly and says why`() = runBlocking {
        rooms.open("claude", "s1")
        val process = env.computer.processes.single()
        rooms.stop("claude")
        assertEquals(RoomState.Stopped, rooms.states.value["claude"])
        assertEquals(StopReason.OWNER, rooms.stops.value["claude"]?.reason)
        assertTrue(env.computer.stopped.contains(process))
        assertEquals(env.ports.revoked.single(), env.ports.exposed.single().targetPort)
        assertTrue(env.phone.stopped.contains("claude"))
    }

    @Test fun `an engine that ends by itself is reported, with nothing lost`() = runBlocking {
        rooms.open("claude", "s1")
        env.computer.processes.single().destroy()
        withTimeout(10_000) {
            while (rooms.states.value["claude"] !is RoomState.Failed) delay(50)
        }
        assertTrue((rooms.states.value["claude"] as RoomState.Failed).why.contains("Nothing was lost"))
        assertEquals(StopReason.ENDED, rooms.stops.value["claude"]?.reason)
    }

    @Test fun `problems are plain sentences`() = runBlocking {
        env.computer.mutableState.value = ComputerState.NotInstalled
        assertEquals(RoomState.Failed("Set up the computer first."), rooms.open("claude", "s1"))
        env.computer.mutableState.value = ComputerState.Ready
        assertEquals(RoomState.Failed("This session is not on this phone yet."), rooms.open("claude", "nope"))
        assertEquals(RoomState.Failed("This session belongs to another agent."), rooms.open("codex", "s1"))
        env.decision = Decision.no("Not enough memory for another agent right now.")
        assertEquals(RoomState.Failed("Not enough memory for another agent right now."), rooms.open("claude", "s1"))
        env.decision = Decision.YES
        dirs.worktree("antigravity", "octo/app", "a1").mkdirs()
        assertEquals(RoomState.Failed("Antigravity is being installed. Try again in a minute."), rooms.open("antigravity", "a1"))
        File(dirs.worktree("claude", "octo/app", "s2").path).deleteRecursively()
        assertEquals(RoomState.Failed(RoomTerminals.MISSING_WORKTREE), rooms.open("claude", "s2"))
        assertTrue(env.computer.commands.isEmpty())
    }

    @Test fun `the hub room runs agy and answers through the bridge without a cookie`() = runBlocking {
        File(dirs.roomHome("antigravity"), ".gemini/bin").mkdirs()
        File(dirs.roomHome("antigravity"), ".gemini/bin/agy").writeText("#!/bin/sh\n")
        dirs.worktree("antigravity", "octo/app", "a1").mkdirs()
        val state = rooms.open("antigravity", "a1")
        assertTrue(state.toString(), state is RoomState.Running)
        val exposed = env.ports.exposed.single()
        assertEquals(emptyMap<String, String>(), env.ports.injected[exposed.targetPort])
        assertEquals(exposed.entryUrl, (state as RoomState.Running).url)
        assertTrue(env.computer.commands.single().argv.contains(RoomEngines.AGY))
    }

    @Test fun `the terminal is guarded by its secret and reused`() = runBlocking {
        val handle = rooms.terminal("s1")
        val exposed = env.ports.exposed.single { it.purpose == "terminal:s1" }
        val secret = env.ports.injected[exposed.targetPort]!!["X-PocketIDE-Secret"]!!
        assertEquals(64, secret.length)
        assertEquals(403, status(exposed.targetPort, null))
        assertEquals(200, status(exposed.targetPort, secret))
        assertEquals(handle, rooms.terminal("s1"))
        assertEquals(1, env.computer.commands.count { it.argv.contains(RoomLayout.TERMINAL_SERVER) })
        assertTrue(dirs.roomBridge("claude").listFiles().orEmpty().none { it.name.endsWith(".secret") })
        rooms.stop("claude")
        assertTrue(env.ports.revoked.contains(exposed.targetPort))
    }

    @Test fun `MCP calls from the room reach the tools`() = runBlocking {
        val handler = env.phone.handlers["mcp"]!!
        val answer = handler("claude", buildJsonObject {
            put("tool", "phone_status")
            put("args", JsonObject(emptyMap()))
            put("cwd", "/work/octo__app/s1")
        })
        assertTrue(answer.jsonObject["text"]!!.jsonPrimitive.content.contains("Everything is allowed"))
        env.phone.handlers["notify"]!!("claude", buildJsonObject {
            put("kind", "needs_you")
            put("text", "Permission to run npm install?")
            put("cwd", "/work/octo__app/s1")
        })
        assertEquals(listOf("claude|s1|Claude Code needs you|Permission to run npm install?"), env.notices)
    }

    @Test fun `delete removes the room's folders`() = runBlocking {
        rooms.open("claude", "s1")
        rooms.delete("claude")
        assertFalse(File(dirs.rooms, "claude").exists())
        assertFalse(dirs.roomWork("claude").exists())
        assertFalse(rooms.states.value.containsKey("claude"))
    }

    private fun status(port: Int, secret: String?): Int {
        val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        if (secret != null) connection.setRequestProperty("X-PocketIDE-Secret", secret)
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun python(): String? = listOf("python3", "/usr/bin/python3").firstOrNull {
        try {
            ProcessBuilder(it, "--version").start().waitFor(10, TimeUnit.SECONDS)
        } catch (missing: java.io.IOException) {
            false
        }
    }

    /** Runs each room program as a local process: stand-in engines, and the real term.py. */
    private class FakeComputer(private val dirs: AppDirs) : Computer {
        val mutableState = MutableStateFlow<ComputerState>(ComputerState.Ready)
        override val state: StateFlow<ComputerState> = mutableState
        val commands = CopyOnWriteArrayList<LinuxCommand>()
        val processes = CopyOnWriteArrayList<Process>()
        val stopped = CopyOnWriteArrayList<Process>()
        val configs = CopyOnWriteArrayList<String>()

        override fun start(command: LinuxCommand): Process {
            commands += command
            val argv = command.argv
            val local = when {
                RoomEngines.CODE_SERVER in argv -> {
                    val config = argv[argv.indexOf("--config") + 1]
                    configs += host(config).readText()
                    engine(argv[argv.indexOf("--bind-addr") + 1].substringAfter(':'))
                }
                RoomEngines.AGY in argv -> engine(argv.first { it.startsWith("--hub-port=") }.substringAfter('='))
                RoomLayout.TERMINAL_SERVER in argv -> listOf(
                    "python3", File(ASSETS, "rooms/term.py").absolutePath,
                    "--port", argv[argv.indexOf("--port") + 1],
                    "--cwd", host(argv[argv.indexOf("--cwd") + 1]).absolutePath,
                    "--web", File(ASSETS, "web/terminal").absolutePath,
                    "--secret-file", host(argv[argv.indexOf("--secret-file") + 1]).absolutePath,
                )
                else -> listOf("true")
            }
            return ProcessBuilder(local).redirectErrorStream(true).start().also { processes += it }
        }

        private fun engine(port: String) = listOf("python3", "-c", ENGINE, port)

        /** A guest path of the claude or antigravity room, on the host. */
        private fun host(guest: String): File {
            val agent = if (commands.last().binds.any { it.hostPath.contains("/antigravity") }) "antigravity" else "claude"
            val path = RoomLayout.hostPath(dirs, agent, guest)
            if (path != null) return path.file
            return File(dirs.roomBridge(agent), guest.removePrefix("${AppDirs.GUEST_BRIDGE}/"))
        }

        override suspend fun run(command: LinuxCommand, onLine: (String) -> Unit): Int = 0
        override fun stop(process: Process) {
            stopped += process
            process.destroyForcibly()
        }
        override suspend fun install() = Unit
        override suspend fun reset() = Unit
        override suspend fun info(): ComputerInfo = throw UnsupportedOperationException()
        override fun sizeBytes() = 0L

        companion object {
            const val ASSETS = "src/main/assets"
            const val ENGINE = """
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = b'{"status":"alive"}' if self.path == '/healthz' else b'<html>hub</html>'
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args):
        pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
"""
        }
    }

    private class FakePorts : PortBridge {
        override val exposed = CopyOnWriteArrayList<BridgedPort>()
        val injected = ConcurrentHashMap<Int, Map<String, String>>()
        val revoked = CopyOnWriteArrayList<Int>()

        override fun expose(port: Int, purpose: String, injectHeaders: Map<String, String>): BridgedPort {
            val bridgePort = port + 1
            val bridged = BridgedPort(port, bridgePort, purpose, "http://127.0.0.1:$bridgePort/_pocketide/enter?t=token", "http://127.0.0.1:$bridgePort")
            exposed += bridged
            injected[port] = injectHeaders
            return bridged
        }
        override fun revoke(port: Int) {
            revoked += port
        }
        override fun isInternal(url: String) = true
        override suspend fun listeners(candidates: Collection<Int>) = emptyList<com.pocketide.bridge.PortListener>()
        override fun shutdown() = Unit
    }

    private class FakePhone : PhoneBridge {
        val started = CopyOnWriteArrayList<String>()
        val stopped = CopyOnWriteArrayList<String>()
        val handlers = ConcurrentHashMap<String, suspend (String, JsonObject) -> JsonElement>()
        override fun start(agentId: String) {
            started += agentId
        }
        override fun stop(agentId: String) {
            stopped += agentId
        }
        override fun handle(op: String, handler: suspend (agentId: String, args: JsonObject) -> JsonElement) {
            handlers[op] = handler
        }
    }

    private class FakeEnv(override val dirs: AppDirs, override val scope: CoroutineScope, private val all: List<SessionRecord>) : RoomsEnv {
        override val computer = FakeComputer(dirs)
        override val portBridge = FakePorts()
        override val phoneBridge = FakePhone()
        val ports get() = portBridge
        val phone get() = phoneBridge
        override val assets: RoomAssets = FolderRoomAssets()
        var decision = Decision.YES
        val notices = CopyOnWriteArrayList<String>()

        override fun now() = System.currentTimeMillis()
        override fun agentInfo(agentId: String): AgentInfo? = null
        override fun agents() = listOf("claude", "codex", "antigravity")
        override fun sessions() = all
        override fun activeSession(agentId: String): String? = null
        override fun project(projectId: String) = Project(id = projectId, owner = "octo", repo = "app", addedAt = 0, lastActivityAt = 0)
        override suspend fun variables(projectId: String) = mapOf("API_URL" to "https://staging.example")
        override fun canStartAgent(agentId: String) = decision
        override fun canStartHeavyWork(what: String) = Decision.YES
        override fun allowDownload(bytes: Long, kind: String) = Decision.YES
        override fun recordDownload(bytes: Long, kind: String) = Unit
        override suspend fun ensureInstalled(agentId: String) = throw IllegalStateException("Open VSX could not be reached.")
        override fun fontSize() = 14
        override fun heapMegabytes() = 512
        override suspend fun gitIdentity() = mapOf("GIT_AUTHOR_NAME" to "Octo")
        override fun notify(agentId: String, sessionId: String?, title: String, text: String) {
            notices += "$agentId|$sessionId|$title|$text"
        }
        override fun phone() = PhoneSnapshot.UNKNOWN
        override fun guard() = Guard.OK
        override fun maxAgents() = 2
        override fun ownerPresent() = true
        override suspend fun autosave(sessionId: String): String? = null
        override suspend fun putOnMain(sessionId: String): PutOnMainResult = PutOnMainResult.Merged
        override fun templates() = emptyList<BuildTemplate>()
        override suspend fun runBuild(projectId: String, templateId: String, ref: String): Long? = null
        override suspend fun recentRuns(projectId: String) = emptyList<WorkflowRun>()
        override suspend fun collect(projectId: String, sessionId: String, runId: Long) = 0
        override suspend fun openPullRequest(project: Project, head: String, title: String, body: String): PullRequest = throw UnsupportedOperationException()
        override suspend fun addMedia(sessionId: String, file: File, name: String): MediaItem = throw UnsupportedOperationException()
    }
}
