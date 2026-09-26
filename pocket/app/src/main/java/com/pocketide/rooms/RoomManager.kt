package com.pocketide.rooms

import com.pocketide.bridge.BridgedPort
import com.pocketide.core.AppDirs
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.model.SessionRecord
import com.pocketide.projects.ProjectTrust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import kotlinx.coroutines.withTimeoutOrNull
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
 * traces). A room sleeps after the idle time without work: no CPU used by its programs or
 * terminals, and no use of its screen ([touch]). What a room is busy with is passed on to the
 * limiter, which never closes a busy room and holds the phone awake only while one works.
 */
internal class RoomManager(
    private val env: RoomsEnv,
    private val sampleMs: Long = SAMPLE_MS,
    /** Time Remote Control's daemon gets to open its ports before they are checked. */
    private val remoteSettleMs: Long = RemoteControl.SETTLE_MS,
    /** How often its ports are checked again while it runs. */
    private val remoteWatchMs: Long = RemoteControl.WATCH_MS,
) : Rooms {
    private val dirs = env.dirs
    private val random = SecureRandom()
    private val procs = ProcFacts()
    private val rings = ConcurrentHashMap<String, OutputRing>()
    private val configBook = ConfigChangeBook(dirs.rooms)
    private val configurator = RoomConfigurator(dirs, env.assets, env::now, configBook, env::claudeChatsInAccount) { agentId, line ->
        ring(agentId).add("[PocketIDE] $line")
    }
    private val terminals = RoomTerminals(env, configurator, ::ring, ::roomEnvironment, ::newSecret, ::settingsForTerminal)
    private val browser = BrowserInstaller(env, configurator, ::afterBrowserInstall)
    private val tools = McpTools(dirs, Ports())
    private val holds = WorkHolds(env::setBusy)
    private val waitedBuilds: MutableSet<Pair<String, Long>> = ConcurrentHashMap.newKeySet()

    /**
     * Google's Remote Control daemon running in a room ([startRemoteControl]), keyed by agent id,
     * from the moment it starts: whatever happens next, a stop reaches it.
     */
    private val remoteDaemons = ConcurrentHashMap<String, Process>()
    private val mutableRemoteControls = MutableStateFlow<Map<String, RemoteControlState>>(emptyMap())
    override val remoteControls: StateFlow<Map<String, RemoteControlState>> = mutableRemoteControls.asStateFlow()

    /** Headless runs (scheduled tasks) going on in each room, keyed by agent id. */
    private val headless = HashMap<String, Int>()

    private val mutableStates = MutableStateFlow<Map<String, RoomState>>(emptyMap())
    override val states: StateFlow<Map<String, RoomState>> = mutableStates.asStateFlow()
    private val mutablePreviewPorts = MutableStateFlow<Map<String, List<Int>>>(emptyMap())
    override val previewPorts: StateFlow<Map<String, List<Int>>> = mutablePreviewPorts.asStateFlow()
    private val mutableStops = MutableStateFlow<Map<String, RoomStop>>(emptyMap())
    override val stops: StateFlow<Map<String, RoomStop>> = mutableStops.asStateFlow()
    private val mutableSleeps = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val sleepsAt: StateFlow<Map<String, Long>> = mutableSleeps.asStateFlow()
    private val mutableProcesses = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val processes: StateFlow<Map<String, Int>> = mutableProcesses.asStateFlow()
    override val configChanges: StateFlow<List<ConfigChange>> = configBook.pending
    override val keptConfig: StateFlow<List<ConfigChange>> = configBook.kept

    private class LiveRoom(
        val profile: RoomProfile,
        val process: Process,
        val port: Int,
        val variables: Map<String, String>,
        /** Someone else's code: the agent asks before running anything and has no browser tools. */
        val careful: Boolean,
        val activity: ActivityClock,
        @Volatile var sessionId: String,
    ) {
        val pid: Int? = ProcFacts.pidOf(process)

        /** Started with Remote Control on: every session it shows is kept in the owner's Claude account. */
        @Volatile var accountChats: Boolean = false

        @Volatile var bridge: BridgedPort? = null

        @Volatile var url: String = ""

        @Volatile var memoryBytes: Long = 0

        @Volatile var signInStamp: Long? = null

        var watcher: Job? = null
    }

    /** The room and session an open asks for, or why they cannot be opened. */
    private sealed interface Opening {
        class Ready(val profile: RoomProfile, val session: SessionRecord) : Opening
        class Refused(val why: String) : Opening
    }

    private val live = ConcurrentHashMap<String, LiveRoom>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Hub rooms whose agy let other apps in, with that agy file's stamp ([agyStamp]) and why it stays closed. */
    private val leakyHubs = ConcurrentHashMap<String, Pair<Long, String>>()
    private val monitorLock = Any()
    private var monitor: Job? = null

    init {
        // Terminal output and Preview traffic are the owner at work in a room: it stays awake.
        env.portBridge.onTraffic { bridge, port ->
            val agentId = RoomTraffic.agentOf(bridge.purpose, bridge.targetPort, port) { sessionId ->
                env.sessions().firstOrNull { it.id == sessionId }?.agentId
            }
            if (agentId != null) touch(agentId)
        }
        env.phoneBridge.handle(MCP_OP) { agentId, args ->
            val writes = McpTools.writes(args)
            if (writes) holds.hold(agentId, WorkHolds.WRITE)
            try {
                tools.call(agentId, args)
            } finally {
                if (writes) holds.release(agentId, WorkHolds.WRITE)
            }
        }
        env.phoneBridge.handle(NOTIFY_OP) { agentId, args ->
            notice(agentId, args)
            JsonObject(emptyMap())
        }
        // What agents added to their settings is on the owner's screen before any room starts.
        env.scope.launch(Dispatchers.IO) { configBook.loadAll() }
    }

    override suspend fun open(agentId: String, sessionId: String): RoomState = open(agentId, sessionId, null)

    /**
     * Engines start detached from the screen that asked: leaving the screen while a room starts
     * stops the waiting, not the start. Room for a new room is made before its lock is taken,
     * because making room stops other rooms, each under its own lock.
     */
    override suspend fun open(agentId: String, sessionId: String, firstPrompt: String?): RoomState = env.scope.async {
        val opening = opening(agentId, sessionId)
        if (opening is Opening.Refused) return@async fail(agentId, opening.why)
        val admitted = live[agentId]?.process?.isAlive != true
        if (admitted) {
            computerProblem()?.let { return@async fail(agentId, it) }
            val room = env.makeRoomFor(agentId)
            if (!room.allowed) return@async fail(agentId, room.reason ?: CANNOT_START)
        }
        val state = lock(agentId).withLock {
            openLocked(agentId, sessionId, admitted).also { opened ->
                if (opened is RoomState.Running && live[agentId]?.accountChats == true) env.keptInClaudeAccount(sessionId)
            }
        }
        if (firstPrompt != null && state is RoomState.Running) offerPrompt(agentId, firstPrompt)
        state
    }.await()

    override fun takesPrompts(agentId: String): Boolean = profile(agentId)?.promptCommand != null

    /** [admitted]: the limiter has just made room for this room, so its phone readings may lag behind. */
    private suspend fun openLocked(agentId: String, sessionId: String, admitted: Boolean): RoomState {
        val (profile, session) = when (val opening = opening(agentId, sessionId)) {
            is Opening.Refused -> return fail(agentId, opening.why)
            is Opening.Ready -> opening.profile to opening.session
        }
        // An engine that ended but was not cleaned up yet gives its port back first.
        live[agentId]?.takeIf { !it.process.isAlive }?.let { shutDown(agentId, it) }
        val current = live[agentId]
        if (current != null) {
            touch(agentId)
            if (current.sessionId == sessionId) return running(current)
            val sameRoom = current.variables == env.variables(session.projectId, agentId) && current.careful == careful(session)
            if (profile.engine == Engine.CODE_SERVER && sameRoom) return showSession(current, session)
            // Switching needs a new engine; a turn in progress is never cut off for it.
            if (current.activity.busyWithin(env.now(), BUSY_WINDOW_MS)) {
                return RoomState.Failed("${profile.name} is still working in another session. Wait until it finishes, or stop it first.")
            }
            shutDown(agentId, current)
            recordStop(agentId, StopReason.SWITCHED, "${profile.name} restarted to open another session.")
        }
        return start(profile, session, admitted = admitted && current == null)
    }

    private fun opening(agentId: String, sessionId: String): Opening {
        val profile = profile(agentId) ?: return Opening.Refused(UNKNOWN_AGENT)
        val session = env.sessions().firstOrNull { it.id == sessionId } ?: return Opening.Refused("This session is not on this phone yet.")
        if (session.agentId != agentId) return Opening.Refused("This session belongs to another agent.")
        return Opening.Ready(profile, session)
    }

    override suspend fun stop(agentId: String) = stopWith(agentId, StopReason.OWNER, "Stopped. Nothing was lost; open it again to continue.")

    override suspend fun stopAll() {
        val agents = live.keys + mutableStates.value.keys + remoteDaemons.keys
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
        lock(agentId).withLock { withContext(Dispatchers.IO) { prepare(profile, carefulNow(agentId)) } }
    }

    override suspend fun delete(agentId: String) {
        require(RoomProfiles.isAgentId(agentId)) { "\"$agentId\" is not an agent id." }
        stopWith(agentId, StopReason.OWNER, "Removed.")
        lock(agentId).withLock {
            withContext(Dispatchers.IO) {
                listOf(File(dirs.rooms, agentId), dirs.roomBridge(agentId), dirs.roomWork(agentId)).forEach(RoomFiles::deleteTree)
                configBook.forget(agentId)
            }
            mutableStates.update { it - agentId }
            mutableStops.update { it - agentId }
            rings.remove(agentId)
        }
    }

    override suspend fun keepConfigChange(change: ConfigChange) {
        if (withContext(Dispatchers.IO) { configBook.keep(change) }) writeConfigNow(change.agentId)
    }

    override suspend fun dropConfigChange(change: ConfigChange) = withContext(Dispatchers.IO) { configBook.drop(change) }

    override suspend fun stopKeepingConfigChange(change: ConfigChange) {
        if (withContext(Dispatchers.IO) { configBook.stopKeeping(change) }) writeConfigNow(change.agentId)
    }

    /**
     * A terminal's programs (the agents' command-line tools among them) read the room's settings
     * too, so a terminal start rebuilds them as an engine start does. A room whose settings cannot
     * be written still gets its terminal: it is where the owner can repair the room.
     */
    private suspend fun settingsForTerminal(session: SessionRecord) {
        val agentId = session.agentId
        val profile = profile(agentId) ?: return
        val careful = live[agentId]?.careful ?: careful(session)
        try {
            withContext(Dispatchers.IO) { configurator.configure(profile, otherAgents(agentId), env.fontSize(), careful) }
        } catch (failed: IOException) {
            ring(agentId).add("[PocketIDE] The room's settings could not be written for the terminal: ${failed.message}")
        } catch (failed: IllegalStateException) {
            ring(agentId).add("[PocketIDE] The room's settings could not be written for the terminal: ${failed.message}")
        } catch (failed: IllegalArgumentException) {
            ring(agentId).add("[PocketIDE] The room's settings could not be written for the terminal: ${failed.message}")
        }
    }

    /** The room's settings written again now, so the owner's choice holds before its next start too. */
    private suspend fun writeConfigNow(agentId: String) {
        if (profile(agentId) == null) return
        try {
            configure(agentId)
        } catch (failed: IOException) {
            ring(agentId).add("[PocketIDE] The room's settings could not be written now; its next start writes them: ${failed.message}")
        } catch (failed: IllegalStateException) {
            ring(agentId).add("[PocketIDE] The room's settings could not be written now; its next start writes them: ${failed.message}")
        }
    }

    override fun touch(agentId: String) {
        val room = live[agentId] ?: return
        room.activity.touch(env.now())
        env.used(agentId)
    }

    override suspend fun restart(agentId: String): RoomState = env.scope.async {
        lock(agentId).withLock {
            val current = live[agentId] ?: return@withLock mutableStates.value[agentId] ?: RoomState.Stopped
            val session = env.sessions().firstOrNull { it.id == current.sessionId }
            shutDown(agentId, current)
            if (session == null) {
                publish(agentId, RoomState.Stopped)
                return@withLock RoomState.Stopped
            }
            start(current.profile, session)
        }
    }.await()

    /**
     * The room is prepared as for a start, except while its engine runs, whose files are not
     * rewritten under it. The bridge stays up and the room awake until the run ends; the bridge
     * goes afterwards only if nothing else of the room is left.
     */
    override suspend fun runHeadless(
        agentId: String,
        projectId: String,
        argv: List<String>,
        workDir: String,
        programEnv: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val profile = profile(agentId) ?: throw IllegalStateException(UNKNOWN_AGENT)
        computerProblem()?.let { throw IllegalStateException(it) }
        val careful = env.trust(projectId) == ProjectTrust.SOMEONE_ELSES
        val variables = env.variables(projectId, agentId)
        var registration = emptyMap<String, String>()
        val command = lock(agentId).withLock {
            val engineRunning = live[agentId]?.process?.isAlive == true
            if (!engineRunning) withContext(Dispatchers.IO) { prepare(profile, careful) }
            // ~/.claude.json is Claude's own state file: it is merged only while Claude is not running.
            if (!engineRunning) registration = mcpRegistration(profile, careful)
            val environment = roomEnvironment(agentId, variables) + registration + programEnv
            headlessStarted(agentId)
            RoomEngines.headless(dirs, agentId, argv, workDir, environment)
        }
        try {
            return env.computer.run(command, onLine)
        } finally {
            withContext(NonCancellable) {
                lock(agentId).withLock { headlessEnded(agentId) }
                if (registration.isNotEmpty()) withContext(Dispatchers.IO) { collectClaudeState(agentId) }
            }
        }
    }

    /**
     * Runs detached from the screen that asked, as an engine start does, so leaving the screen
     * stops the waiting, not the start.
     */
    override suspend fun startRemoteControl(agentId: String): String {
        val profile = profile(agentId)?.takeIf { it.engine == Engine.AGY_HUB } ?: throw IllegalStateException(RemoteControl.ONLY_ANTIGRAVITY)
        return env.scope.async { lock(agentId).withLock { startRemoteControlLocked(profile) } }.await()
    }

    private suspend fun startRemoteControlLocked(profile: RoomProfile): String {
        val agentId = profile.agentId
        if (remoteDaemons[agentId]?.isAlive == true) return RemoteControl.DASHBOARD
        publishRemoteControl(agentId, RemoteControlState.Starting)
        try {
            val problem = computerProblem() ?: engineProblem(profile)
            if (problem != null) throw IllegalStateException(problem)
            return startDaemon(agentId)
        } catch (failed: IllegalStateException) {
            publishRemoteControl(agentId, RemoteControlState.Off(failed.message ?: RemoteControl.notStarted(emptyList())))
            throw failed
        }
    }

    /** Starts the daemon, tracked at once, and keeps it only when every port it opened passes. */
    private suspend fun startDaemon(agentId: String): String {
        // The daemon lives on after this screen: the engine's service keeps the computer running while it is on.
        if (!env.keepEngineAlive()) ring(agentId).add("[PocketIDE] Android did not let the engine's service start now.")
        val known = listeningPorts().map { it.port }.toMutableSet()
        // Its own folders first, its own /home among them: proot refuses a bind whose folder is missing.
        withContext(Dispatchers.IO) { RoomLayout.hostFolders(dirs, agentId).forEach { it.mkdirs() } }
        val command = RoomEngines.headless(dirs, agentId, RemoteControl.startCommand(), AppDirs.GUEST_HOME, roomEnvironment(agentId, emptyMap()))
        val process = startDaemonProcess(command)
        remoteDaemons[agentId] = process
        env.scope.launch(Dispatchers.IO) { pump(agentId, process) }
        env.scope.launch { awaitDaemonEnd(agentId, process) }
        delay(remoteSettleMs)
        val why = if (process.isAlive) daemonPortProblem(agentId, known) else RemoteControl.notStarted(ring(agentId).last(LAST_WORDS))
        if (why != null) {
            endDaemon(agentId, process)
            throw IllegalStateException(why)
        }
        publishRemoteControl(agentId, RemoteControlState.On)
        env.scope.launch { watchDaemon(agentId, process, known) }
        return RemoteControl.DASHBOARD
    }

    private suspend fun startDaemonProcess(command: LinuxCommand): Process = try {
        withContext(Dispatchers.IO) { env.computer.start(command) }
    } catch (failed: IOException) {
        throw IllegalStateException(RemoteControl.notStarted(listOfNotNull(failed.message)), failed)
    }

    /**
     * Checks the daemon's ports again for as long as it runs: it may open one any time, when a
     * conversation starts or after it restarted itself. It is turned off at the first that fails.
     */
    private suspend fun watchDaemon(agentId: String, process: Process, known: MutableSet<Int>) {
        while (remoteDaemons[agentId] === process) {
            delay(remoteWatchMs)
            if (remoteDaemons[agentId] !== process) return
            val why = daemonPortProblem(agentId, known) ?: continue
            val turnedOff = lock(agentId).withLock {
                (remoteDaemons[agentId] === process && endDaemon(agentId, process)).also { ended ->
                    if (ended) publishRemoteControl(agentId, RemoteControlState.Off(why))
                }
            }
            if (turnedOff) env.notify(agentId, null, "Remote Control turned off", why)
            return
        }
    }

    /**
     * Why the ports that started listening since [known] was taken keep Remote Control off, or
     * null; the ones that pass join [known]. PocketIDE's own ports (engines, terminals, the port
     * bridge's) and the dev servers agents announced are not the daemon's.
     */
    private suspend fun daemonPortProblem(agentId: String, known: MutableSet<Int>): String? {
        val own = live.values.map { it.port }.toSet() + terminals.ports() + mutablePreviewPorts.value.values.flatten()
        val opened = listeningPorts().filter { it.port !in known && it.port !in own }
        if (opened.isEmpty()) return null
        val answers = withContext(Dispatchers.IO) { opened.associate { it.port to Loopback.get(it.port, "/") } }
        val why = RemoteControl.problem(answers, opened.filter { it.onNetwork }.map { it.port }.toSet())
        if (why == null) {
            known += answers.keys
        } else {
            ring(agentId).add("[PocketIDE] Remote Control opened ports ${answers.keys.sorted()}; it was stopped again.")
        }
        return why
    }

    private suspend fun listeningPorts() = env.portBridge.listeners(allPorts())

    /** Waits for the daemon to end; if nobody stopped it, says so. */
    private suspend fun awaitDaemonEnd(agentId: String, process: Process) {
        runInterruptible(Dispatchers.IO) { process.waitFor() }
        lock(agentId).withLock {
            if (!remoteDaemons.remove(agentId, process)) return@withLock
            ring(agentId).add("[PocketIDE] Remote Control ended by itself.")
            publishRemoteControl(agentId, RemoteControlState.Off(RemoteControl.STOPPED_BY_ITSELF))
        }
    }

    /** Stops the daemon exactly, if it is still the room's; true when it was. Called under the room's lock. */
    private fun endDaemon(agentId: String, process: Process): Boolean {
        val tracked = remoteDaemons.remove(agentId, process)
        env.computer.stop(process)
        return tracked
    }

    override suspend fun stopRemoteControl(agentId: String) = lock(agentId).withLock {
        remoteDaemons.remove(agentId)?.let(env.computer::stop)
        mutableRemoteControls.update { it - agentId }
    }

    private fun publishRemoteControl(agentId: String, state: RemoteControlState) = mutableRemoteControls.update { it + (agentId to state) }

    /** Every TCP port: Android may hide the socket table, and then each one is tried on the loopback. */
    private fun allPorts(): List<Int> = (1..MAX_PORT).toList()

    private fun headlessStarted(agentId: String) {
        val first = synchronized(headless) {
            val count = headless[agentId] ?: 0
            headless[agentId] = count + 1
            count == 0
        }
        if (first) env.setBusy(agentId, WorkHolds.TASK, true)
        env.phoneBridge.start(agentId)
        touch(agentId)
    }

    /** Called under the room's lock, so an engine cannot start between the check and the stop. */
    private fun headlessEnded(agentId: String) {
        val last = synchronized(headless) {
            val count = (headless[agentId] ?: 1) - 1
            if (count > 0) headless[agentId] = count else headless.remove(agentId)
            count <= 0
        }
        if (!last) return
        env.setBusy(agentId, WorkHolds.TASK, false)
        touch(agentId)
        if (live[agentId] == null && !terminals.hasAny(agentId)) env.phoneBridge.stop(agentId)
    }

    private fun runningHeadless(agentId: String): Boolean = synchronized(headless) { agentId in headless }

    override fun recentOutput(agentId: String): List<String> = rings[agentId]?.last(DIAGNOSTIC_LINES).orEmpty()

    override suspend fun signOutAll(): List<String> {
        stopAll()
        val agents = (env.agents() + RoomProfiles.OFFICIAL).distinct().filter(RoomProfiles::isAgentId)
        val signedIn = withContext(Dispatchers.IO) {
            agents.filter { agentId ->
                val file = SignOuts.signInFile(agentId) ?: return@filter false
                // Only whether the file is there: it is never read.
                RoomFiles(dirs.roomHome(agentId), guardSecrets = true).isFile(file)
            }
        }
        return signedIn.map { agentId -> lock(agentId).withLock { signOut(agentId) } }
    }

    // --- starting

    /**
     * Starts [profile]'s engine on [session]. Unless the limiter has just [admitted] it (after
     * closing rooms, whose memory its readings do not show yet), the limiter is asked first.
     */
    private suspend fun start(profile: RoomProfile, session: SessionRecord, port: Int? = null, admitted: Boolean = false): RoomState {
        val agentId = profile.agentId
        mutableStops.update { it - agentId }
        publish(agentId, RoomState.Starting("Checking the computer"))
        computerProblem()?.let { return fail(agentId, it) }
        if (!admitted) {
            val decision = env.canStartAgent(agentId)
            if (!decision.allowed) return fail(agentId, decision.reason ?: CANNOT_START)
        }
        val worktree = "${AppDirs.projectDirName(session.projectId)}/${session.id}"
        val hasWorktree = withContext(Dispatchers.IO) { RoomFiles(dirs.roomWork(agentId), guardSecrets = false).isDirectory(worktree) }
        if (!hasWorktree) return fail(agentId, RoomTerminals.MISSING_WORKTREE)
        val careful = careful(session)
        // Read as the settings written next read it: Remote Control connects from this start on.
        val accountChats = agentId == RoomProfiles.CLAUDE && env.claudeChatsInAccount()
        publish(agentId, RoomState.Starting("Preparing the room"))
        try {
            withContext(Dispatchers.IO) { prepare(profile, careful) }
        } catch (failed: IOException) {
            return fail(agentId, "The room could not be prepared: ${reason(failed)}")
        } catch (failed: IllegalArgumentException) {
            return fail(agentId, "The room could not be prepared: ${reason(failed)}")
        } catch (failed: IllegalStateException) {
            return fail(agentId, "The room could not be prepared: ${reason(failed)}")
        }
        engineProblem(profile)?.let { return fail(agentId, it) }

        val variables = env.variables(session.projectId, agentId)
        val chosenPort = port ?: withContext(Dispatchers.IO) { Loopback.freePort() }
        val guestWorktree = AppDirs.guestWorktree(session.projectId, session.id)
        val environment = roomEnvironment(agentId, variables) + engineEnvironment(profile, careful)
        val secret = newSecret()
        val bridgeFiles = RoomFiles(dirs.roomBridge(agentId), guardSecrets = false)
        val secretFile = RoomEngines.secretFile(RoomEngines.CODE_SERVER_KIND, chosenPort)
        val command = when (profile.engine) {
            Engine.CODE_SERVER -> {
                withContext(Dispatchers.IO) { bridgeFiles.write(secretFile, RoomEngines.codeServerConfig(secret)) }
                RoomEngines.codeServer(dirs, profile, guestWorktree, chosenPort, environment)
            }
            Engine.AGY_HUB -> {
                // Found letting other apps in before: the same agy is not started again only to be refused.
                val stamp = withContext(Dispatchers.IO) { agyStamp(profile) }
                leakyHubs[agentId]?.takeIf { it.first == stamp }?.let { (_, why) -> return fail(agentId, why) }
                RoomEngines.hub(dirs, profile, guestWorktree, chosenPort, environment, token = secret)
            }
        }

        publish(agentId, RoomState.Starting("Starting ${profile.name}"))
        // While the owner is most likely on the screen: Android 12+ refuses this from the background.
        if (!env.keepEngineAlive()) ring(agentId).add("[PocketIDE] Android did not let the engine's service start now.")
        env.phoneBridge.start(agentId)
        val process = try {
            withContext(Dispatchers.IO) { env.computer.start(command) }
        } catch (failed: IOException) {
            return couldNotStart(profile, bridgeFiles, secretFile, failed)
        } catch (failed: IllegalStateException) {
            return couldNotStart(profile, bridgeFiles, secretFile, failed)
        } catch (failed: IllegalArgumentException) {
            return couldNotStart(profile, bridgeFiles, secretFile, failed)
        }
        val room = LiveRoom(profile, process, chosenPort, variables, careful, ActivityClock(env.now(), ::idleLimitMs), session.id)
        room.accountChats = accountChats
        live[agentId] = room
        env.scope.launch(Dispatchers.IO) { pump(agentId, process) }

        publish(agentId, RoomState.Starting("Waiting for ${profile.name}"))
        val ready = try {
            awaitReady(room)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // room.py has run by now: an engine answers only after it.
                collectClaudeState(agentId)
                // code-server has read its password by now, or will never need it.
                bridgeFiles.delete(secretFile)
            }
        }
        if (!ready) {
            val alive = process.isAlive
            shutDown(agentId, room)
            val lastWords = ring(agentId).last(LAST_WORDS).takeIf { it.isNotEmpty() }
                ?.let { " Its last words: ${it.joinToString(" / ")}" }.orEmpty()
            val why = if (alive) "did not answer within ${READY_MS / 1000} seconds." else "stopped while starting."
            return fail(agentId, "${profile.name} $why$lastWords")
        }
        if (profile.engine == Engine.AGY_HUB) {
            hubProblem(room, secret)?.let { why ->
                shutDown(agentId, room)
                return fail(agentId, why)
            }
        }
        // A new secret each launch, so each launch gets a new bridge and token too.
        val inject = when (profile.engine) {
            Engine.CODE_SERVER -> mapOf("Cookie" to RoomEngines.sessionCookie(secret))
            Engine.AGY_HUB -> mapOf(RoomEngines.HUB_TOKEN_HEADER to secret)
        }
        val bridge = try {
            env.portBridge.expose(chosenPort, RoomTraffic.agentPurpose(agentId), inject)
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
        countProcesses(agentId, room)
        room.watcher = env.scope.launch { watch(agentId, room) }
        ensureMonitor()
        return running(room).also { publish(agentId, it) }
    }

    private suspend fun couldNotStart(profile: RoomProfile, bridgeFiles: RoomFiles, secretFile: String, failed: Exception): RoomState {
        withContext(Dispatchers.IO) { bridgeFiles.delete(secretFile) }
        return fail(profile.agentId, "${profile.name} could not start: ${reason(failed)}")
    }

    /** Folders, PocketIDE's tools inside the computer, and the room's configuration. */
    private fun prepare(profile: RoomProfile, careful: Boolean) {
        RoomLayout.hostFolders(dirs, profile.agentId).forEach { folder ->
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create ${folder.name}.")
        }
        configurator.installTools()
        configurator.configure(profile, otherAgents(profile.agentId), env.fontSize(), careful)
    }

    private fun otherAgents(agentId: String) =
        (env.agents() + RoomProfiles.OFFICIAL).distinct().filter { it != agentId && RoomProfiles.isAgentId(it) }

    private fun careful(session: SessionRecord) = env.trust(session.projectId) == ProjectTrust.SOMEONE_ELSES

    /** How careful the room is now: as its running engine, else as the session it would open. */
    private fun carefulNow(agentId: String): Boolean {
        live[agentId]?.let { return it.careful }
        val sessionId = env.activeSession(agentId) ?: return false
        return env.sessions().firstOrNull { it.id == sessionId }?.let(::careful) ?: false
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
            Engine.AGY_HUB -> if (agyInstalled(profile)) null else install(profile) { agyInstalled(it) }
            Engine.CODE_SERVER -> {
                if (!File(dirs.rootfs, RoomEngines.CODE_SERVER.removePrefix("/")).exists()) {
                    "The computer's code-server is missing. Repair the computer from the Computer screen."
                } else if (extensionInstalled(profile)) {
                    null
                } else {
                    install(profile) { extensionInstalled(it) }
                }
            }
        }
    }

    /** Installs the agent now, the room waiting; null once [installed] says it is there. */
    private suspend fun install(profile: RoomProfile, installed: (RoomProfile) -> Boolean): String? {
        publish(profile.agentId, RoomState.Starting("Installing ${profile.name}"))
        return try {
            env.ensureInstalled(profile.agentId)
            if (installed(profile)) null else "${profile.name} is not installed yet. Try again in a minute."
        } catch (failed: IllegalStateException) {
            "${profile.name} is not installed yet: ${reason(failed)}"
        } catch (failed: IOException) {
            "${profile.name} could not be installed: ${reason(failed)}"
        }
    }

    private fun agyInstalled(profile: RoomProfile): Boolean =
        RoomFiles(dirs.roomHome(profile.agentId), guardSecrets = true).isFile(RoomEngines.AGY.removePrefix("${AppDirs.GUEST_HOME}/"))

    /** When the room's agy file last changed: an update, or a repair, gives it a new one. */
    private fun agyStamp(profile: RoomProfile): Long? =
        RoomFiles(dirs.roomHome(profile.agentId), guardSecrets = true).lastModified(RoomEngines.AGY.removePrefix("${AppDirs.GUEST_HOME}/"))

    /**
     * Null when the started hub keeps its [token] from callers that do not have it, as the
     * terminal keeps its secret; otherwise why its screen stays closed. Its page is asked for as
     * another app would ask (no token), then as the bridge will (with it).
     */
    private suspend fun hubProblem(room: LiveRoom, token: String): String? {
        val (withoutToken, withToken) = withContext(Dispatchers.IO) {
            Loopback.get(room.port, "/", maxBody = HUB_PAGE_BYTES) to
                Loopback.get(room.port, "/", maxBody = HUB_PAGE_BYTES, headers = mapOf(RoomEngines.HUB_TOKEN_HEADER to token))
        }
        val agentId = room.profile.agentId
        val name = room.profile.name
        return when (RoomEngines.hubGuard(withoutToken, withToken, token)) {
            HubGuard.GUARDED -> null
            HubGuard.GIVES_TOKEN_AWAY -> openToOtherApps(room, givesTokenAway(name), "served this launch's token to a request that did not have it")
            HubGuard.ANSWERS_WITHOUT_TOKEN -> openToOtherApps(room, answersWithoutToken(name), "answered a request without this launch's token")
            HubGuard.REFUSES_TOKEN ->
                "$name's screen could not open: its hub refused this launch's key. " +
                    "An update of $name may have changed how its screen signs in."
            HubGuard.NO_ANSWER -> "$name stopped answering while it started."
        }
    }

    /** Remembers that this agy lets other apps in, so it is not started again only to be refused; [why] for the owner. */
    private suspend fun openToOtherApps(room: LiveRoom, why: String, what: String): String {
        val agentId = room.profile.agentId
        withContext(Dispatchers.IO) { agyStamp(room.profile) }?.let { leakyHubs[agentId] = it to why }
        ring(agentId).add("[PocketIDE] The hub $what.")
        return why
    }

    private fun extensionInstalled(profile: RoomProfile): Boolean {
        val prefix = profile.extensionId?.lowercase()?.plus("-") ?: return false
        return configurator.extensionFolders(profile.agentId).any { it.lowercase().startsWith(prefix) }
    }

    /**
     * Settings of the engine itself: Node's heap limit on a phone, and for room.py Claude's MCP
     * entries, what the owner kept in ~/.claude.json and where to list what it takes out.
     */
    private fun engineEnvironment(profile: RoomProfile, careful: Boolean): Map<String, String> = buildMap {
        if (profile.engine == Engine.CODE_SERVER) put("NODE_OPTIONS", "--max-old-space-size=${env.heapMegabytes()}")
        putAll(mcpRegistration(profile, careful))
    }

    /** What room.py took out of Claude's ~/.claude.json at its last run waits for the owner. */
    private fun collectClaudeState(agentId: String) {
        if (agentId != RoomProfiles.CLAUDE) return
        try {
            configurator.collectClaudeState(agentId)
        } catch (failed: IOException) {
            ring(agentId).add("[PocketIDE] What was taken out of ~/.claude.json could not be listed: ${failed.message}")
        }
    }

    /**
     * Claude's MCP entries, which room.py merges into ~/.claude.json before the program starts,
     * with what the owner kept there and where to list what it takes out.
     */
    private fun mcpRegistration(profile: RoomProfile, careful: Boolean): Map<String, String> =
        if (profile.agentId == RoomProfiles.CLAUDE) {
            mapOf(
                "POCKETIDE_CLAUDE_MCP" to ConfigFiles.claudeMcpEntries(configurator.mcpServers(careful)),
                "POCKETIDE_CLAUDE_KEEP" to configurator.claudeStateKept(profile.agentId),
                "POCKETIDE_HELD_REPORT" to ClaudeState.GUEST_REPORT,
            )
        } else {
            emptyMap()
        }

    private suspend fun roomEnvironment(agentId: String, projectId: String): Map<String, String> =
        roomEnvironment(agentId, env.variables(projectId, agentId))

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

    /**
     * Hands [prompt] to the agent's companion through a file in the room's bridge folder; the
     * companion opens the agent with it. A prompt that cannot be written is only logged.
     */
    private suspend fun offerPrompt(agentId: String, prompt: String) {
        if (!takesPrompts(agentId)) return
        val text = prompt.take(MAX_PROMPT_CHARS)
        try {
            withContext(Dispatchers.IO) {
                RoomFiles(dirs.roomBridge(agentId), guardSecrets = false)
                    .write(RoomEngines.promptFile(newSecret().take(PROMPT_ID_CHARS)), RoomEngines.promptRequest(text))
            }
        } catch (failed: IOException) {
            ring(agentId).add("[PocketIDE] The first prompt could not be handed over: ${failed.message}")
        }
    }

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
        remoteDaemons.remove(agentId)?.let(env.computer::stop)
        mutableRemoteControls.update { it - agentId }
        // A scheduled task still running in the room keeps using PocketIDE's tools.
        if (!runningHeadless(agentId)) env.phoneBridge.stop(agentId)
        publish(agentId, RoomState.Stopped)
    }

    /** Ends the engine exactly (its proot and everything under it) and closes its port. */
    private fun shutDown(agentId: String, room: LiveRoom) {
        live.remove(agentId, room)
        room.watcher?.cancel()
        env.portBridge.revoke(room.port)
        env.computer.stop(room.process)
        holds.clear(agentId)
        waitedBuilds.removeAll { it.first == agentId }
        mutableSleeps.update { it - agentId }
        mutableProcesses.update { it - agentId }
    }

    /** Runs the agent's own sign-out in its room; one sentence for the owner. */
    private suspend fun signOut(agentId: String): String {
        val name = profile(agentId)?.name ?: agentId
        val anyway = "Its sign-in on this phone is deleted anyway."
        val argv = withContext(Dispatchers.IO) { SignOuts.command(agentId, configurator.extensionFolders(agentId)) }
            ?: return "$name has no sign-out PocketIDE can run. $anyway"
        computerProblem()?.let { return "$name was not signed out: $it $anyway" }
        val command = LinuxCommand(
            argv = argv,
            binds = RoomLayout.binds(dirs, agentId),
            env = RoomLayout.environment(agentId, emptyMap(), emptyMap()),
            workDir = AppDirs.GUEST_HOME,
        )
        val code = try {
            withContext(Dispatchers.IO) { RoomLayout.hostFolders(dirs, agentId).forEach { it.mkdirs() } }
            withTimeoutOrNull(SIGN_OUT_MS) { env.computer.run(command) { ring(agentId).add("[sign-out] $it") } }
        } catch (failed: IOException) {
            ring(agentId).add("[PocketIDE] The sign-out could not start: ${failed.message}")
            -1
        } catch (failed: IllegalStateException) {
            ring(agentId).add("[PocketIDE] The sign-out could not start: ${failed.message}")
            -1
        } catch (failed: IllegalArgumentException) {
            ring(agentId).add("[PocketIDE] The sign-out could not start: ${failed.message}")
            -1
        }
        return when (code) {
            0 -> "$name signed out."
            null -> "$name did not finish signing out within ${SIGN_OUT_MS / 1000} seconds. $anyway"
            else -> "$name could not sign out. $anyway"
        }
    }

    // --- activity

    private fun idleLimitMs(): Long = env.idleSleepMinutes() * 60_000L

    private fun ensureMonitor() = synchronized(monitorLock) {
        if (monitor == null) monitor = env.scope.launch(Dispatchers.IO) { watchActivity() }
    }

    private suspend fun watchActivity() {
        while (true) {
            delay(sampleMs)
            synchronized(monitorLock) {
                if (live.isEmpty() && terminals.isEmpty()) {
                    holds.holding(WorkHolds.COMMAND).forEach { holds.set(it, WorkHolds.COMMAND, false) }
                    monitor = null
                    return
                }
            }
            val now = env.now()
            val commands = terminals.sample(now, procs)
            for (agentId in commands + holds.holding(WorkHolds.COMMAND)) holds.set(agentId, WorkHolds.COMMAND, agentId in commands)
            val tasks = synchronized(headless) { headless.keys.toSet() }
            // Said again each time: the limiter forgets the work of rooms that were not running yet.
            for (agentId in tasks) env.setBusy(agentId, WorkHolds.TASK, true)
            for ((agentId, room) in live) {
                try {
                    sample(agentId, room, now, otherWork = agentId in commands || agentId in tasks)
                } catch (failed: IOException) {
                    // One unreadable sample must not end the watch over every room.
                    ring(agentId).add("[PocketIDE] Activity could not be read: ${failed.message}")
                } catch (failed: IllegalStateException) {
                    ring(agentId).add("[PocketIDE] Activity could not be read: ${failed.message}")
                }
            }
            mutableSleeps.value = live.mapNotNull { (agentId, room) -> room.activity.sleepsAt()?.let { agentId to it } }.toMap()
        }
    }

    /**
     * [otherWork]: a terminal command or a scheduled task ran in the room since the last sample.
     * A build the agent waits on, or a write in progress, uses no CPU here but is work all the same.
     */
    private suspend fun sample(agentId: String, room: LiveRoom, now: Long, otherWork: Boolean) {
        val pid = room.pid ?: return
        val tree = procs.tree(pid)
        val working = room.activity.sample(now, procs.cpuTicks(tree))
        holds.set(agentId, WorkHolds.TURN, working)
        if (working || otherWork || holds.waits(agentId)) {
            room.activity.touch(now)
            env.used(agentId)
        }
        countProcesses(agentId, room)
        val memory = procs.residentBytes(tree)
        if (kotlin.math.abs(memory - room.memoryBytes) > MEMORY_STEP_BYTES) {
            room.memoryBytes = memory
            if (live[agentId] === room) publish(agentId, running(room))
        }
        if (room.activity.isIdle(now)) {
            val minutes = env.idleSleepMinutes()
            stopWith(agentId, StopReason.IDLE, "Stopped after $minutes minutes without activity. Nothing was lost; open it again to continue.")
            return
        }
        if (room.profile.engine == Engine.AGY_HUB) restartAfterSignIn(agentId, room)
    }

    /** The room's Linux processes (engine and terminals), for the phantom-process budget. */
    private fun countProcesses(agentId: String, room: LiveRoom) {
        val count = env.computer.liveProcesses(room.process) + terminals.processes(agentId)
        if (live[agentId] === room) mutableProcesses.update { it + (agentId to count) }
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
        if (agentId != RoomProfiles.ANTIGRAVITY) {
            null
        } else {
            RoomFiles(dirs.roomHome(agentId), guardSecrets = true).lastModified(HUB_SIGN_IN)
        }

    override suspend fun signedIn(agentId: String): Boolean? {
        val file = SignOuts.signInFile(agentId) ?: HUB_SIGN_IN.takeIf { agentId == RoomProfiles.ANTIGRAVITY } ?: return null
        return withContext(Dispatchers.IO) { RoomFiles(dirs.roomHome(agentId), guardSecrets = true).isFile(file) }
    }

    // --- tools

    private suspend fun afterBrowserInstall() {
        for (agentId in (env.agents() + RoomProfiles.OFFICIAL).distinct()) {
            val profile = profile(agentId) ?: continue
            try {
                val careful = carefulNow(agentId)
                withContext(Dispatchers.IO) { prepare(profile, careful) }
                // ~/.claude.json is Claude's own state file: it is merged only while Claude is not running.
                if (agentId == RoomProfiles.CLAUDE && live[agentId] == null) {
                    env.computer.run(RoomEngines.setUpOnly(dirs, agentId, engineEnvironment(profile, careful))) { ring(agentId).add(it) }
                    withContext(Dispatchers.IO) { collectClaudeState(agentId) }
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
        touch(agentId)
        env.notify(agentId, session?.id, title, text)
    }

    private inner class Ports : McpPorts {
        override fun sessions(agentId: String) = env.sessions().filter { it.agentId == agentId }
        override fun currentSession(agentId: String) = live[agentId]?.sessionId ?: env.activeSession(agentId)
        override fun project(projectId: String) = env.project(projectId)
        override fun phone() = env.phone()
        override fun guard() = env.guard()
        override fun maxAgents() = env.maxAgents()
        override fun ownerPresent() = env.ownerPresent()
        override suspend fun autosave(sessionId: String) = env.autosave(sessionId)
        override suspend fun putOnMain(sessionId: String) = env.putOnMain(sessionId)
        override fun templates() = env.templates()
        override suspend fun runBuild(projectId: String, templateId: String, ref: String) = env.runBuild(projectId, templateId, ref)
        override suspend fun progress(projectId: String, runId: Long) = env.buildProgress(projectId, runId)
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

        override suspend fun browser(agentId: String): String {
            if (carefulNow(agentId)) return BROWSER_OFF
            return browser.request(agentId)
        }

        override fun listeners(): List<String> = listOf("/proc/net/tcp", "/proc/net/tcp6").flatMap { path ->
            try {
                File(path).readLines().drop(1)
            } catch (unreadable: IOException) {
                emptyList()
            }
        }

        /** A build the agent waits on keeps its room busy until the agent sees it end, or for at most an hour. */
        override fun buildStarted(agentId: String, runId: Long) {
            if (!waitedBuilds.add(agentId to runId)) return
            holds.hold(agentId, WorkHolds.BUILD)
            env.scope.launch {
                delay(BUILD_WAIT_MAX_MS)
                buildEnded(agentId, runId)
            }
        }

        override fun buildEnded(agentId: String, runId: Long) {
            if (waitedBuilds.remove(agentId to runId)) holds.release(agentId, WorkHolds.BUILD)
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
        const val SAMPLE_MS = 60_000L
        const val BUSY_WINDOW_MS = 2 * 60_000L
        const val READY_MS = 90_000L
        const val POLL_MS = 500L
        const val MEMORY_STEP_BYTES = 16L * 1024 * 1024
        const val DIAGNOSTIC_LINES = 50
        const val LAST_WORDS = 3
        const val MAX_PREVIEW_PORTS = 10
        const val SECRET_BYTES = 32
        const val PROMPT_ID_CHARS = 16
        const val MAX_PROMPT_CHARS = 20_000
        const val SIGN_OUT_MS = 30_000L
        const val BUILD_WAIT_MAX_MS = 60 * 60_000L
        const val HUB_SIGN_IN = ".gemini/jetski-standalone-oauth-token"
        const val HUB_PAGE_BYTES = 512 * 1024
        const val MAX_PORT = 65_535
        const val CANNOT_START = "The phone cannot take another agent right now."
        const val UNKNOWN_AGENT = "PocketIDE does not know this agent. Add it from More agents first."
        const val BROWSER_OFF = "The test browser stays off in this project: it is someone else's code, and a web page could " +
            "steer you. The owner can turn it on by marking the project as theirs in PocketIDE."

        fun givesTokenAway(name: String) = "$name stays closed: this version of its hub gives its key to any app on this phone " +
            "that asks, and with that key another app could use $name in your projects. It opens once an update of $name fixes this."

        fun answersWithoutToken(name: String) = "$name stays closed: this version of its hub answers any app on this phone without " +
            "asking for its key, so another app could use $name in your projects. It opens once an update of $name fixes this."
    }
}
