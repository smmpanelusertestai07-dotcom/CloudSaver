package com.pocketide.schedule

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Ist
import com.pocketide.core.Redact
import com.pocketide.linux.Bind
import com.pocketide.linux.LinuxCommand
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Something the owner can act on, in one plain sentence. */
class ScheduleException(message: String) : Exception(message)

/** What a scheduled run uses from other modules and from Android. */
internal interface RunPorts {
    val clock: Clock
    val dirs: AppDirs

    suspend fun startSession(projectId: String, agentId: String, title: String): SessionRecord
    fun session(sessionId: String): SessionRecord?

    /** Variables for the room (never Secrets). */
    suspend fun variables(projectId: String, agentId: String): Map<String, String>

    /** Runs in Linux; the process is stopped when the call is cancelled. */
    suspend fun runInLinux(command: LinuxCommand, onLine: (String) -> Unit): Int

    /** Why heavy work may not start now (battery, heat), or null. */
    fun heavyWorkRefusal(): String?

    /** Saves the output as a TEXT item in the session's Media. */
    suspend fun saveOutput(sessionId: String, file: File)

    /** Pushes the session's branch (the agent may have committed); best effort. */
    suspend fun afterRun(sessionId: String)

    fun scratchFile(): File

    fun notify(taskId: String, heading: String, text: String)

    suspend fun recordRun(taskId: String, at: Long, sessionId: String)
}

/** How one run ended, for the notification and the tests. */
data class RunOutcome(val sessionId: String, val succeeded: Boolean, val timedOut: Boolean, val exitCode: Int?)

/**
 * One run of a scheduled task: a new session, the agent's command-line mode in that session's
 * room with a time limit, the output saved in the session's Media, and a notification.
 */
internal class ScheduledRun(private val ports: RunPorts, private val timeLimitMs: Long = HeadlessCommand.TIME_LIMIT_MINUTES * 60_000L) {

    /** Starts the session a run will use, so "Run now" can open it straight away. */
    suspend fun newSession(task: ScheduledTask): SessionRecord {
        if (HeadlessCommand.argv(task.agentId, task.prompt, "/") == null) throw ScheduleException(NO_HEADLESS)
        return ports.startSession(task.projectId, task.agentId, "${task.title} · ${Ist.dateTime(ports.clock.now())}")
    }

    suspend fun run(task: ScheduledTask, existingSessionId: String? = null): RunOutcome {
        ports.heavyWorkRefusal()?.let { throw ScheduleException(it) }
        val session = existingSessionId?.let { ports.session(it) } ?: newSession(task)
        val worktree = AppDirs.guestWorktree(session.projectId, session.id)
        val argv = HeadlessCommand.argv(task.agentId, task.prompt, worktree) ?: throw ScheduleException(NO_HEADLESS)
        val command = LinuxCommand(
            argv = argv,
            binds = roomBinds(task.agentId),
            env = ports.variables(task.projectId, task.agentId) + HeadlessCommand.env(task.agentId),
            workDir = worktree,
        )
        val startedAt = ports.clock.now()
        val lines = ArrayList<String>()
        var bytes = 0L
        var exitCode: Int? = null
        var timedOut = false
        var succeeded = false
        try {
            exitCode = withTimeoutOrNull(timeLimitMs) {
                ports.runInLinux(command) { line ->
                    synchronized(lines) {
                        if (bytes < MAX_OUTPUT_BYTES) {
                            val clean = Redact.text(line)
                            lines += clean
                            bytes += clean.length + 1
                        }
                    }
                }
            }
            timedOut = exitCode == null
        } finally {
            // Cancelled or not, what the agent wrote so far is kept and the run is recorded.
            withContext(NonCancellable) {
                val output = synchronized(lines) { lines.toList() }
                val cut = synchronized(lines) { bytes >= MAX_OUTPUT_BYTES }
                val ok = exitCode?.let { HeadlessCommand.succeeded(task.agentId, it, output) } ?: false
                succeeded = ok
                val ended = when {
                    timedOut -> "Stopped after ${HeadlessCommand.TIME_LIMIT_MINUTES} minutes, the time limit for a scheduled task."
                    exitCode == null -> "Stopped before it finished (Android ended the job, or the phone left the charger or Wi-Fi)."
                    ok -> "Finished."
                    else -> "Ended with code $exitCode."
                }
                save(task, startedAt, session.id, output, ended, cut)
                ports.recordRun(task.id, startedAt, session.id)
                ports.afterRun(session.id)
                ports.notify(task.id, if (ok) "Scheduled task finished" else "Scheduled task needs a look", "${task.title}: $ended Open the session to review it.")
            }
        }
        return RunOutcome(session.id, succeeded, timedOut, exitCode)
    }

    private suspend fun save(task: ScheduledTask, startedAt: Long, sessionId: String, output: List<String>, ended: String, cut: Boolean) {
        val file = ports.scratchFile()
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { w ->
                w.appendLine("Scheduled task: ${task.title}")
                w.appendLine("Agent: ${task.agentId}   Started: ${Ist.dateTime(startedAt)}")
                w.appendLine(ended)
                w.appendLine()
                output.forEach(w::appendLine)
                if (cut) w.appendLine("[Output cut at ${MAX_OUTPUT_BYTES / 1024} KB.]")
            }
            ports.saveOutput(sessionId, file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The session and its commits still show what happened.
        } finally {
            file.delete()
        }
    }

    /** The room as the agent knows it: its own home and temp, its worktrees, the shared clones. */
    private fun roomBinds(agentId: String): List<Bind> {
        val dirs = ports.dirs
        return buildList {
            add(Bind(dirs.roomHome(agentId).absolutePath, AppDirs.GUEST_HOME))
            add(Bind(dirs.roomTmp(agentId).absolutePath, "/tmp"))
            add(Bind(dirs.repos.absolutePath, AppDirs.GUEST_REPOS))
            add(Bind(dirs.roomWork(agentId).absolutePath, AppDirs.GUEST_WORK))
            val bridge = dirs.roomBridge(agentId)
            if (bridge.isDirectory) add(Bind(bridge.absolutePath, AppDirs.GUEST_BRIDGE))
        }
    }

    companion object {
        const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024
        const val NO_HEADLESS = "This agent has no command-line mode, so it cannot run scheduled tasks. Choose Claude Code, Codex or Antigravity."
    }
}
