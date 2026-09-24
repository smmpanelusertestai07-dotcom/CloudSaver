package com.pocketide.rooms

import com.pocketide.bridge.BridgedPort
import com.pocketide.core.AppDirs
import com.pocketide.linux.ComputerState
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The rooms: one proot session per agent, each binding only its own home, temporary folder,
 * bridge folder and worktrees, plus the shared bare repositories (RoomLayout). A code-server room
 * serves every session of its agent, one folder per session; the Antigravity room runs agy's hub
 * for one session at a time.
 *
 * Engines are started detached from any screen and stopped exactly (their proot and everything it
 * traces). A room sleeps after [IDLE_MS] without work: no CPU used by its programs or terminals,
 * and no use of its screen ([touch]).
 */
internal class RoomManager(private val env: RoomsEnv) : Rooms {
    private val dirs = env.dirs
    private val random = SecureRandom()
    private val procs = ProcFacts()
    private val rings = ConcurrentHashMap<String, OutputRing>()
    private val configurator = RoomConfigurator(dirs, env.assets, env::now) { agentId, line -> ring(agentId).add("[PocketIDE] $line") }
    private val terminals = RoomTerminals(env, configurator, ::ring, ::roomEnvironment, ::newSecret)
    private val browser = BrowserInstaller(env, configurator, ::afterBrowserInstall)
    private val tools = McpTools(dirs, Ports())

    private val mutableStates = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    override val states: StateFlow<Map<String, RoomState>> = mutableStates.asStateFlow()
    private val mutablePreviewPorts = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    override val previewPorts: StateFlow<Map<String, List<Int>>> = mutablePreviewPorts.asStateFlow()
    private val mutableStops = MutableStateFlow<Map<String, RoomStop>>(emptyMap())
    override val stops: StateFlow<Map<String, RoomStop>> = mutableStops.asStateFlow()
    private val mutableSleeps = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val sleepsAt: StateFlow<Map<String, Long>> = mutableSleeps.asStateFlow()

    private class LiveRoom(
        val profile: RoomProfile,
        val process: Process,
        val port: Int,
        val variables: Map<String, String>,
        val activity: ActivityClock,
        @Volatile var sessionId: String,
    ) {
        val pid: Int? = ProcFacts.pidOf(process)
        @Volatile var bridge: BridgedPort? = null
        @Volatile var url: String = ""
        @Volatile var memoryBytes: Long = 0
        @Volatile var signInStamp: Long? = null
        var watcher: Job? = null
    }

    private val live = ConcurrentHashMap<String, LiveRoom>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val monitorLock = Any()
    private var monitor: Job? = null

    init {
        env.phoneBridge.handle(MCP_OP) { agentId, args -> tools.call(agentId, args) }
        env.phoneBridge.handle(NOTIFY_OP) { agentId, args ->
            notice(agentId, args)
            JsonObject(emptyMap())
        }
    }

    override suspend fun open(agentId: String, sessionId: String): RoomState = lock(agentId).withLock {
        val profile = profile(agentId) ?: return@withLock fail(agentId, "PocketIDE does not know this agent. Add it from More agents first.")
        val session = env.sessions().firstOrNull { it.id == sessionId }
            ?: return@withLock fail(agentId, "This session is not on this phone yet.")
        if (session.agentId != agentId) return@withLock fail(agentId, "This session belongs to another agent.")
        // An engine that ended but was not cleaned up yet gives its port back first.
        live[agentId]?.takeIf { !it.process.isAlive }?.let { shutDown(agentId, it) }
        val current = live[agentId]
        if (current != null) {
            current.activity.touch(env.now())
            if (current.sessionId == sessionId) return@withLock running(current)
            val sameVariables = current.variables == env.variables(session.projectId)
            if (profile.engine == Engine.CODE_SERVER && sameVariables) return@withLock showSession(current, session)
            // Switching needs a new engine; a turn in progress is never cut off for it.
            if (current.activity.busyWithin(env.now(), BUSY_WINDOW_MS)) {
                return@withLock RoomState.Failed("${profile.name} is still working in another session. Wait until it finishes, or stop it first.")
            }
            shutDown(agentId, current)
            recordStop(agentId, StopReason.SWITCHED, "${profile.name} restarted to open another session.")
        }
        start(profile, session)
    }

    override suspend fun stop(agentId: String) = stopWith(agentId, StopReason.OWNER, "Stopped. Nothing was lost; open it again to continue.")

    override suspend fun stopAll() {
        val agents = live.keys + mutableStates.value.keys
        for (agentId in agents) stopWith(agentId, StopReason.OWNER, "Stopped. Nothing was lost; open it again to continue.")
        terminals.stopAll()
    }

    override suspend fun terminal(sessionId: String): TerminalHandle {
        val session = env.sessions().firstOrNull { it.id == sessionId } ?: throw IllegalStateException("This session is not on this phone yet.")
        computerProblem()?.let { throw IllegalStateException(it) }
        val handle = terminals.open(session)
        ensureMonitor()
        return handle
    }

    override suspend fun configure(agentId: String) {
        val profile = profile(agentId) ?: throw IllegalArgumentException("PocketIDE does not know the agent \"$agentId\".")
        withContext(Dispatchers.IO) { prepare(profile) }
    }

    override suspend fun delete(agentId: String) {
        require(RoomProfiles.isAgentId(agentId)) { "\"$agentId\" is not an agent id." }
        stopWith(agentId, StopReason.OWNER, "Removed.")
        lock(agentId).withLock {
            withContext(Dispatchers.IO) {
                listOf(File(dirs.rooms, agentId), dirs.roomBridge(agentId), dirs.roomWork(agentId)).forEach(RoomFiles::deleteTree)
            }
            mutableStates.update { it - agentId }
            mutableStops.update { it - agentId }
            rings.remove(agentId)
        }
    }

    override fun touch(agentId: String) {
        live[agentId]?.activity?.touch(env.now())
    }

    override suspend fun restart(agentId: String): RoomState = lock(agentId).withLock {
        val current = live[agentId] ?: return@withLock mutableStates.value[agentId] ?: RoomState.Stopped
        val session = env.sessions().firstOrNull { it.id == current.sessionId }
        shutDown(agentId, current)
        if (session == null) {
            publish(agentId, RoomState.Stopped)
            return@withLock RoomState.Stopped
        }
        start(current.profile, session)
    }

    override fun recentOutput(agentId: String): List<String> = rings[agentId]?.last(DIAGNOSTIC_LINES).orEmpty()

    // --- starting

    private suspend fun start(profile: RoomProfile, session: SessionRecord, port: Int? = null): RoomState {
        val agentId = profile.agentId
        mutableStops.update { it - agentId }
        publish(agentId, RoomState.Starting("Checking the computer"))
        computerProblem()?.let { return fail(agentId, it) }
        val decision = env.canStartAgent(agentId)
        if (!decision.allowed) return fail(agentId, decision.reason ?: "The phone cannot take another agent right now.")
        val worktree = "${AppDirs.projectDirName(session.projectId)}/${session.id}"
        val hasWorktree = withContext(Dispatchers.IO) { RoomFiles(dirs.roomWork(agentId), guardSecrets = false).isDirectory(worktree) }
        if (!hasWorktree) return fail(agentId, RoomTerminals.MISSING_WORKTREE)
        publish(agentId, RoomState.Starting("Preparing the room"))
        try {
            withContext(Dispatchers.IO) { prepare(profile) }
        } catch (failed: IOException) {
            return fail(agentId, "The room could not be prepared: ${failed.message ?: "a file could not be written"}.")
        }
        engineProblem(profile)?.let { return fail(agentId, it) }

        val variables = env.variables(session.projectId)
        val chosenPort = port ?: withContext(Dispatchers.IO) { Loopback.freePort() }
        val guestWorktree = AppDirs.guestWorktree(session.projectId, session.id)
        val environment = roomEnvironment(agentId, variables) + engineEnvironment(profile)
        val secret = newSecret()
        val bridgeFiles = RoomFiles(dirs.roomBridge(agentId), guardSecrets = false)
        val secretFile = RoomEngines.secretFile(RoomEngines.CODE_SERVER_KIND, chosenPort)
        val command = when (profile.engine) {
            Engine.CODE_SERVER -> {
                withContext(Dispatchers.IO) { bridgeFiles.write(secretFile, RoomEngines.codeServerConfig(secret)) }
                RoomEngines.codeServer(dirs, profile, guestWorktree, chosenPort, environment)
            }
            Engine.AGY_HUB -> RoomEngines.hub(dirs, profile, guestWorktree, chosenPort, environment)
        }

        publish(agentId, RoomState.Starting("Starting ${profile.name}"))
        env.phoneBridge.start(agentId)
        val process = try {
            withContext(Dispatchers.IO) { env.computer.start(command) }
        } catch (failed: Exception) {
            withContext(Dispatchers.IO) { bridgeFiles.delete(secretFile) }
            return fail(agentId, "${profile.name} could not start: ${reason(failed)}")
        }
        val room = LiveRoom(profile, process, chosenPort, variables, ActivityClock(env.now(), IDLE_MS), session.id)
        live[agentId] = room
        env.scope.launch(Dispatchers.IO) { pump(agentId, process) }

        publish(agentId, RoomState.Starting("Waiting for ${profile.name}"))
        val ready = try {
            awaitReady(room)
        } finally {
            // code-server has read its password by now, or will never need it.
            withContext(NonCancellable + Dispatchers.IO) { bridgeFiles.delete(secretFile) }
        }
        if (!ready) {
            val alive = process.isAlive
            shutDown(agentId, room)
            val lastWords = ring(agentId).last(1).firstOrNull()?.let { " Its last words: $it" }.orEmpty()
            val why = if (alive) "did not answer within ${READY_MS / 1000} seconds." else "stopped while starting."
            return fail(agentId, "${profile.name} $why$lastWords")
        }
        val inject = if (profile.engine == Engine.CODE_SERVER) mapOf("Cookie" to RoomEngines.sessionCookie(secret)) else emptyMap()
        val bridge = try {
            env.portBridge.expose(chosenPort, "agent:$agentId", inject)
        } catch (failed: IllegalStateException) {
            shutDown(agentId, room)
            return fail(agentId, failed.message ?: "The agent's screen could not be opened.")
        }
        room.bridge = bridge
        room.url = urlFor(room, session)
        withContext(Dispatchers.IO) {
            room.signInStamp = hubSignInStamp(agentId)
            room.memoryBytes = room.pid?.let { procs.residentBytes(procs.tree(it)) } ?: 0
        }
        room.watcher = env.scope.launch { watch(agentId, room) }
        ensureMonitor()
        return running(room).also { publish(agentId, it) }
    }

    /** Folders, PocketIDE's tools inside the computer, and the room's configuration. */
    private fun prepare(profile: RoomProfile) {
        RoomLayout.hostFolders(dirs, profile.agentId).forEach { folder ->
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create ${folder.name}.")
        }
        configurator.installTools()
        val others = (env.agents() + RoomProfiles.OFFICIAL).distinct().filter { it != profile.agentId && RoomProfiles.isAgentId(it) }
        configurator.configure(profile, others, env.fontSize())
    }

    private fun computerProblem(): String? = when (val state = env.computer.state.value) {
        ComputerState.Ready, is ComputerState.Updating -> null
        ComputerState.NotInstalled -> "Set up the computer first."
        is ComputerState.Installing -> "The computer is still being set up. Try again when it is ready."
        is ComputerState.Broken -> "${state.why} ${state.fix}"
    }

    /** Null when the room's engine and agent are installed; otherwise what the owner should know. */
    private suspend fun engineProblem(profile: RoomProfile): String? = withContext(Dispatchers.IO) {
        when (profile.engine) {
            Engine.AGY_HUB -> {
                val home = RoomFiles(dirs.roomHome(profile.agentId), guardSecrets = true)
                if (home.isFile(RoomEngines.AGY.removePrefix("${AppDirs.GUEST_HOME}/"))) null
                else "Antigravity is being installed. Try again in a minute."
            }
            Engine.CODE_SERVER -> {
                if (!File(dirs.rootfs, RoomEngines.CODE_SERVER.removePrefix("/")).exists()) {
                    "The computer's code-server is missing. Repair the computer from the Computer screen."
                } else if (extensionInstalled(profile)) {
                    null
                } else {
                    publish(profile.agentId, RoomState.Starting("Installing ${profile.name}"))
                    try {
                        env.ensureInstalled(profile.agentId)
                        if (extensionInstalled(profile)) null else "${profile.name} is not installed yet. Try again in a minute."
                    } catch (failed: IllegalStateException) {
                        "${profile.name} is not installed yet: ${reason(failed)}"
                    } catch (failed: IOException) {
                        "${profile.name} could not be installed: ${reason(failed)}"
                    }
                }
            }
        }
    }

    private fun extensionInstalled(profile: RoomProfile): Boolean {
        val prefix = profile.extensionId?.lowercase()?.plus("-") ?: return false
        return configurator.extensionFolders(profile.agentId).any { it.lowercase().startsWith(prefix) }
    }

    /** Settings of the engine itself: Node's heap limit on a phone, and Claude's MCP entries for room.py. */
    private fun engineEnvironment(profile: RoomProfile): Map<String, String> = buildMap {
        if (profile.engine == Engine.CODE_SERVER) put("NODE_OPTIONS", "--max-old-space-size=${env.heapMegabytes()}")
        if (profile.agentId == RoomProfiles.CLAUDE) put("POCKETIDE_CLAUDE_MCP", ConfigFiles.claudeMcpEntries(configurator.mcpServers()))
    }

    private suspend fun roomEnvironment(agentId: String, projectId: String): Map<String, String> =
        roomEnvironment(agentId, env.variables(projectId))

    private suspend fun roomEnvironment(agentId: String, variables: Map<String, String>): Map<String, String> =
        RoomLayout.environment(agentId, variables, env.gitIdentity()) { name ->
            ring(agentId).add("[PocketIDE] The Variable $name is not given to the room: it would change how the room itself runs.")
        }

    private suspend fun awaitReady(room: LiveRoom): Boolean {
        val deadline = env.now() + READY_MS
        val path = RoomEngines.readyPath(room.profile.engine)
        while (room.process.isAlive) {
            val answer = withContext(Dispatchers.IO) { Loopback.get(room.port, path) }
            if (RoomEngines.ready(room.profile.engine, answer)) return true
            if (env.now() >= deadline) return false
            delay(POLL_MS)
        }
        return false
    }

    private fun pump(agentId: String, process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { ring(agentId).add(it) }
        } catch (closed: IOException) {
            // The engine ended; its output ends with it.
        }
    }

    // --- running

    private fun showSession(room: LiveRoom, session: SessionRecord): RoomState {
        room.sessionId = session.id
        room.url = urlFor(room, session)
        return running(room).also { publish(room.profile.agentId, it) }
    }

    private fun urlFor(room: LiveRoom, session: SessionRecord): String {
        val bridge = room.bridge ?: return ""
        if (room.profile.engine != Engine.CODE_SERVER) return bridge.entryUrl
        val folder = URLEncoder.encode(AppDirs.guestWorktree(session.projectId, session.id), "UTF-8")
        return bridge.entryUrlTo("/?folder=$folder")
    }

    private fun running(room: LiveRoom) = RoomState.Running(room.url, room.sessionId, room.memoryBytes)

    /** Waits for the engine to end; if nobody stopped it, says so. */
    private suspend fun watch(agentId: String, room: LiveRoom) {
        runInterruptible(Dispatchers.IO) { room.process.waitFor() }
        lock(agentId).withLock {
            if (live[agentId] !== room) return@withLock
            shutDown(agentId, room)
            val message = "${room.profile.name} stopped by itself. Nothing was lost; open it again to continue."
            recordStop(agentId, StopReason.ENDED, message)
            publish(agentId, RoomState.Failed(message))
        }
    }

    private suspend fun stopWith(agentId: String, reason: StopReason, message: String) = lock(agentId).withLock {
        live[agentId]?.let { room ->
            shutDown(agentId, room)
            recordStop(agentId, reason, message)
        }
        terminals.stopAgent(agentId)
        env.phoneBridge.stop(agentId)
        publish(agentId, RoomState.Stopped)
    }

    /** Ends the engine exactly (its proot and everything under it) and closes its port. */
    private fun shutDown(agentId: String, room: LiveRoom) {
        live.remove(agentId, room)
        room.watcher?.cancel()
        env.portBridge.revoke(room.port)
        env.computer.stop(room.process)
        mutableSleeps.update { it - agentId }
    }

    // --- activity

    private fun ensureMonitor() = synchronized(monitorLock) {
        if (monitor == null) monitor = env.scope.launch(Dispatchers.IO) { watchActivity() }
    }

    private suspend fun watchActivity() {
        while (true) {
            delay(SAMPLE_MS)
            synchronized(monitorLock) {
                if (live.isEmpty() && terminals.isEmpty()) {
                    monitor = null
                    return
                }
            }
            val now = env.now()
            for ((agentId, room) in live) sample(agentId, room, now)
            terminals.sample(now, procs)
            mutableSleeps.value = live.mapValues { (_, room) -> room.activity.sleepsAt() }
        }
    }

    private suspend fun sample(agentId: String, room: LiveRoom, now: Long) {
        val pid = room.pid ?: return
        val tree = procs.tree(pid)
        room.activity.sample(now, procs.cpuTicks(tree + terminals.pids(agentId, procs)))
        val memory = procs.residentBytes(tree)
        if (kotlin.math.abs(memory - room.memoryBytes) > MEMORY_STEP_BYTES) {
            room.memoryBytes = memory
            if (live[agentId] === room) publish(agentId, running(room))
        }
        if (room.activity.isIdle(now)) {
            stopWith(agentId, StopReason.IDLE, "Stopped after ${IDLE_MS / 60_000} minutes without activity. Nothing was lost; open it again to continue.")
            return
        }
        if (room.profile.engine == Engine.AGY_HUB) restartAfterSignIn(agentId, room)
    }

    /**
     * Antigravity's hub reads its sign-in once, when it starts: after the owner signs in, the hub
     * is restarted once, on the same port so the screen's address stays the same.
     */
    private suspend fun restartAfterSignIn(agentId: String, room: LiveRoom) {
        val stamp = withContext(Dispatchers.IO) { hubSignInStamp(agentId) } ?: return
        if (stamp == room.signInStamp) return
        lock(agentId).withLock {
            if (live[agentId] !== room) return@withLock
            val session = env.sessions().firstOrNull { it.id == room.sessionId } ?: return@withLock
            ring(agentId).add("[PocketIDE] Antigravity signed in; its hub restarts to read the sign-in.")
            shutDown(agentId, room)
            start(room.profile, session, port = room.port)
        }
    }

    /** When the hub's sign-in file last changed (read from its metadata only), or null. */
    private fun hubSignInStamp(agentId: String): Long? =
        if (agentId != RoomProfiles.ANTIGRAVITY) null
        else RoomFiles(dirs.roomHome(agentId), guardSecrets = true).lastModified(HUB_SIGN_IN)

    // --- tools

    private suspend fun afterBrowserInstall() {
        for (agentId in (env.agents() + RoomProfiles.OFFICIAL).distinct()) {
            val profile = profile(agentId) ?: continue
            try {
                withContext(Dispatchers.IO) { prepare(profile) }
                // ~/.claude.json is Claude's own state file: it is merged only while Claude is not running.
                if (agentId == RoomProfiles.CLAUDE && live[agentId] == null) {
                    env.computer.run(RoomEngines.setUpOnly(dirs, agentId, engineEnvironment(profile))) { ring(agentId).add(it) }
                }
            } catch (failed: IOException) {
                ring(agentId).add("[PocketIDE] The browser tools could not be registered: ${failed.message}")
            } catch (failed: IllegalStateException) {
                ring(agentId).add("[PocketIDE] The browser tools could not be registered: ${failed.message}")
            }
        }
    }

    private fun notice(agentId: String, args: JsonObject) {
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val kind = (args["kind"] as? JsonPrimitive)?.contentOrNull
        val cwd = (args["cwd"] as? JsonPrimitive)?.contentOrNull
        val session = runCatching { tools.session(agentId, cwd) }.getOrNull()
        val name = profile(agentId)?.name ?: agentId
        val title = if (kind == "needs_you") "$name needs you" else "$name finished"
        live[agentId]?.activity?.touch(env.now())
        env.notify(agentId, session?.id, title, text)
    }

    private inner class Ports : McpPorts {
        override fun sessions(agentId: String) = env.sessions().filter { it.agentId == agentId }
        override fun currentSession(agentId: String) = live[agentId]?.sessionId ?: env.activeSession(agentId)
        override fun project(projectId: String) = env.project(projectId)
        override fun phone() = env.phone()
        override fun guard() = env.guard()
        override fun maxAgents() = env.maxAgents()
        override fun heavyWork(what: String) = env.canStartHeavyWork(what)
        override suspend fun autosave(sessionId: String) = env.autosave(sessionId)
        override suspend fun putOnMain(sessionId: String) = env.putOnMain(sessionId)
        override fun templates() = env.templates()
        override suspend fun runBuild(projectId: String, templateId: String, ref: String) = env.runBuild(projectId, templateId, ref)
        override suspend fun recentRuns(projectId: String) = env.recentRuns(projectId)
        override suspend fun collect(projectId: String, sessionId: String, runId: Long) = env.collect(projectId, sessionId, runId)
        override suspend fun openPullRequest(project: com.pocketide.model.Project, head: String, title: String, body: String) =
            env.openPullRequest(project, head, title, body)
        override suspend fun addMedia(sessionId: String, file: File, name: String) = env.addMedia(sessionId, file, name)
        override fun announcePort(sessionId: String, port: Int) = mutablePreviewPorts.update { ports ->
            val known = ports[sessionId].orEmpty()
            if (port in known) ports else ports + (sessionId to (known + port).takeLast(MAX_PREVIEW_PORTS))
        }
        override fun ownPorts(): Set<Int> =
            env.portBridge.exposed.flatMap { listOf(it.bridgePort, it.targetPort) }.toSet() + live.values.map { it.port } + terminals.ports()
        override suspend fun browser(agentId: String) = browser.request(agentId)
        override fun listeners(): List<String> = listOf("/proc/net/tcp", "/proc/net/tcp6").flatMap { path ->
            try {
                File(path).readLines().drop(1)
            } catch (unreadable: IOException) {
                emptyList()
            }
        }
    }

    // --- small helpers

    private fun profile(agentId: String): RoomProfile? = RoomProfiles.of(agentId, env.agentInfo(agentId))

    private fun lock(agentId: String): Mutex = locks.getOrPut(agentId) { Mutex() }

    private fun ring(agentId: String): OutputRing = rings.getOrPut(agentId) { OutputRing() }

    private fun publish(agentId: String, state: RoomState) = mutableStates.update { it + (agentId to state) }

    private fun fail(agentId: String, why: String): RoomState.Failed {
        ring(agentId).add("[PocketIDE] $why")
        return RoomState.Failed(why).also { publish(agentId, it) }
    }

    private fun recordStop(agentId: String, reason: StopReason, message: String) =
        mutableStops.update { it + (agentId to RoomStop(reason, env.now(), message)) }

    private fun newSecret(): String = ByteArray(SECRET_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun reason(failed: Exception): String = failed.message?.takeIf { it.isNotBlank() } ?: "an unexpected error."

    private companion object {
        const val MCP_OP = "mcp"
        const val NOTIFY_OP = "notify"
        const val IDLE_MS = 15 * 60_000L
        const val SAMPLE_MS = 60_000L
        const val BUSY_WINDOW_MS = 2 * 60_000L
        const val READY_MS = 90_000L
        const val POLL_MS = 500L
        const val MEMORY_STEP_BYTES = 16L * 1024 * 1024
        const val DIAGNOSTIC_LINES = 50
        const val MAX_PREVIEW_PORTS = 10
        const val SECRET_BYTES = 32
        const val HUB_SIGN_IN = ".gemini/jetski-standalone-oauth-token"
    }
}
