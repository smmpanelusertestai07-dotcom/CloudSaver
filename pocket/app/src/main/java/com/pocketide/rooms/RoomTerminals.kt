package com.pocketide.rooms

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The `>_` terminals: one term.py per session, in that session's room (the same binds as the
 * agent), on its own loopback port behind the port bridge. term.py refuses every request that
 * lacks this launch's secret header, which only the bridge adds, so other apps on the phone that
 * find the port get 403 and no shell.
 */
internal class RoomTerminals(
    private val env: RoomsEnv,
    private val configurator: RoomConfigurator,
    private val output: (agentId: String) -> OutputRing,
    private val environment: suspend (agentId: String, projectId: String) -> Map<String, String>,
    private val newSecret: () -> String,
) {
    private class Terminal(
        val sessionId: String,
        val agentId: String,
        val process: Process,
        val pid: Int?,
        val port: Int,
        val handle: TerminalHandle,
        val activity: ActivityClock,
    ) {
        var watcher: Job? = null
    }

    private val live = ConcurrentHashMap<String, Terminal>()
    private val lock = Mutex()

    fun isEmpty() = live.isEmpty()

    fun ports(): Set<Int> = live.values.map { it.port }.toSet()

    /** Linux processes of [agentId]'s terminals: each term.py's proot and everything under it. */
    fun processes(agentId: String): Int = live.values.filter { it.agentId == agentId }.sumOf { env.computer.liveProcesses(it.process) }

    suspend fun open(session: SessionRecord): TerminalHandle = lock.withLock {
        live[session.id]?.takeIf { it.process.isAlive }?.let { existing ->
            existing.activity.touch(env.now())
            return existing.handle
        }
        live.remove(session.id)?.let(::close)
        if (live.size >= MAX_TERMINALS) live.values.minByOrNull { it.activity.lastActivityAt }?.let(::close)
        start(session)
    }

    fun stopAgent(agentId: String) = live.values.filter { it.agentId == agentId }.forEach(::close)

    fun stopAll() = live.values.toList().forEach(::close)

    /**
     * Records CPU use and closes terminals nobody used for the idle time. Returns the agents whose
     * terminals ran something since the last sample (a command is running there).
     */
    fun sample(now: Long, procs: ProcFacts): Set<String> {
        val working = HashSet<String>()
        for (terminal in live.values.toList()) {
            val ticks = terminal.pid?.let { procs.cpuTicks(procs.tree(it)) } ?: 0L
            if (terminal.activity.sample(now, ticks)) working += terminal.agentId
            if (terminal.activity.isIdle(now)) {
                output(terminal.agentId).add("[PocketIDE] A terminal nobody used for a while was closed.")
                close(terminal)
            }
        }
        return working
    }

    private suspend fun start(session: SessionRecord): TerminalHandle {
        val agentId = session.agentId
        val dirs = env.dirs
        val worktree = "${AppDirs.projectDirName(session.projectId)}/${session.id}"
        check(RoomFiles(dirs.roomWork(agentId), guardSecrets = false).isDirectory(worktree)) { MISSING_WORKTREE }
        val port = Loopback.freePort()
        val secret = newSecret()
        val bridgeFiles = RoomFiles(dirs.roomBridge(agentId), guardSecrets = false)
        val secretFile = RoomEngines.secretFile(RoomEngines.TERMINAL_KIND, port)
        withContext(Dispatchers.IO) {
            RoomLayout.hostFolders(dirs, agentId).forEach { it.mkdirs() }
            configurator.installTools()
            bridgeFiles.write(secretFile, secret)
        }
        env.phoneBridge.start(agentId)
        val command = RoomEngines.terminal(
            dirs, agentId, AppDirs.guestWorktree(session.projectId, session.id), port, environment(agentId, session.projectId),
        )
        val process = try {
            withContext(Dispatchers.IO) { env.computer.start(command) }
        } catch (failed: IllegalStateException) {
            withContext(Dispatchers.IO) { bridgeFiles.delete(secretFile) }
            throw failed
        } catch (failed: IOException) {
            withContext(Dispatchers.IO) { bridgeFiles.delete(secretFile) }
            throw IllegalStateException("The terminal could not start: ${failed.message}")
        }
        env.scope.launch(Dispatchers.IO) { pump(agentId, process) }
        var ready = false
        try {
            awaitGuarded(process, port)
            ready = true
        } finally {
            if (!ready) {
                env.computer.stop(process)
                withContext(NonCancellable + Dispatchers.IO) { bridgeFiles.delete(secretFile) }
            }
        }
        val bridge = env.portBridge.expose(port, RoomTraffic.terminalPurpose(session.id), mapOf(SECRET_HEADER to secret))
        val terminal = Terminal(
            sessionId = session.id,
            agentId = agentId,
            process = process,
            pid = ProcFacts.pidOf(process),
            port = port,
            handle = TerminalHandle(bridge.entryUrl, session.id),
            activity = ActivityClock(env.now(), IDLE_MS),
        )
        terminal.watcher = env.scope.launch {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
            if (live.remove(terminal.sessionId, terminal)) env.portBridge.revoke(terminal.port)
        }
        live[session.id] = terminal
        return terminal.handle
    }

    /**
     * Waits until term.py answers, and checks that it refuses a request without the secret: only
     * then is its port handed to the bridge.
     */
    private suspend fun awaitGuarded(process: Process, port: Int) {
        val deadline = env.now() + READY_MS
        while (true) {
            check(process.isAlive) { "The terminal stopped while starting." }
            val answer = withContext(Dispatchers.IO) { Loopback.get(port, "/") }
            if (answer != null) {
                check(answer.status == FORBIDDEN) { "Something else answered on the terminal's port. Try again." }
                return
            }
            check(env.now() < deadline) { "The terminal did not start within ${READY_MS / 1000} seconds." }
            delay(POLL_MS)
        }
    }

    private fun pump(agentId: String, process: Process) {
        try {
            process.inputStream.bufferedReader().forEachLine { output(agentId).add("[terminal] $it") }
        } catch (closed: IOException) {
            // The terminal ended; its output ends with it.
        }
    }

    private fun close(terminal: Terminal) {
        live.remove(terminal.sessionId, terminal)
        terminal.watcher?.cancel()
        env.portBridge.revoke(terminal.port)
        env.computer.stop(terminal.process)
    }

    companion object {
        const val SECRET_HEADER = "X-PocketIDE-Secret"
        private const val FORBIDDEN = 403
        private const val MAX_TERMINALS = 3
        private const val READY_MS = 30_000L
        private const val POLL_MS = 300L
        const val IDLE_MS = 15 * 60_000L
        const val MISSING_WORKTREE = "This session's folder is missing. Open the session from its project to bring it back."
    }
}
