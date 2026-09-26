package com.pocketide.schedule

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Ist
import com.pocketide.core.Redact
import com.pocketide.model.LockReason
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

    suspend fun startSession(projectId: String, agentId: String, title: String): SessionRecord
    suspend fun session(sessionId: String): SessionRecord?

    /**
     * Runs [argv] in the agent's room as the room's own programs run (its binds, its environment
     * with the project's Variables, its launcher, PocketIDE's tools); [programEnv] is the CLI's own
     * settings. The process is stopped when the call is cancelled.
     */
    suspend fun runInRoom(
        agentId: String,
        projectId: String,
        argv: List<String>,
        workDir: String,
        programEnv: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int

    /** Why heavy work may not start now (battery, heat), or null. */
    fun heavyWorkRefusal(): String?

    /**
     * Why the app is locked now, GitHub and Drive asked afresh (access removed, another phone took
     * over, storage full), or null. Offline locks nothing.
     */
    suspend fun lockNow(): LockReason?

    /** True for a project marked "Someone else's", once this phone's project list is read. */
    suspend fun someoneElses(projectId: String): Boolean

    /** Saves the output as a TEXT item in the session's Media. */
    suspend fun saveOutput(sessionId: String, file: File)

    /** Pushes the session's branch (the agent may have committed); best effort. */
    suspend fun afterRun(sessionId: String)

    fun scratchFile(): File

    fun notify(taskId: String, heading: String, text: String)

    /**
     * Records a run: with [ended] false it is marked as started, before the agent does anything;
     * with true it becomes the task's last run and the mark goes.
     */
    suspend fun recordRun(taskId: String, at: Long, sessionId: String, ended: Boolean = true)
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
        refusal(task)?.let { throw ScheduleException(it) }
        return ports.startSession(task.projectId, task.agentId, "${task.title} · ${Ist.dateTime(ports.clock.now())}")
    }

    /**
     * Runs [task] in [existingSessionId] or a new session. [background]: Android did not let the
     * run into the foreground, so it has only the time a plain job gets, and stops before
     * Android cuts it off.
     */
    suspend fun run(task: ScheduledTask, existingSessionId: String? = null, background: Boolean = false): RunOutcome {
        val session = sessionFor(task, existingSessionId)
        val worktree = AppDirs.guestWorktree(session.projectId, session.id)
        val argv = HeadlessCommand.argv(task.agentId, task.prompt, worktree) ?: throw ScheduleException(NO_HEADLESS)
        val limitMs = if (background) minOf(timeLimitMs, BACKGROUND_LIMIT_MS) else timeLimitMs
        val startedAt = ports.clock.now()
        ports.recordRun(task.id, startedAt, session.id, ended = false)
        val output = Output()
        var exitCode: Int? = null
        var timedOut = false
        var succeeded = false
        try {
            exitCode = withTimeoutOrNull(limitMs) {
                ports.runInRoom(task.agentId, session.projectId, argv, worktree, HeadlessCommand.env(task.agentId), output::add)
            }
            timedOut = exitCode == null
        } finally {
            // Cancelled or not, what the agent wrote so far is kept and the run is recorded.
            withContext(NonCancellable) {
                val lines = output.lines()
                succeeded = exitCode?.let { HeadlessCommand.succeeded(task.agentId, it, lines) } ?: false
                val text = endedText(timedOut, background, exitCode, succeeded)
                finish(task, Ending(startedAt, session.id, text, lines, output.cut, succeeded))
            }
        }
        return RunOutcome(session.id, succeeded, timedOut, exitCode)
    }

    /** The session the run uses, once nothing says it may not run now. */
    private suspend fun sessionFor(task: ScheduledTask, existingSessionId: String?): SessionRecord {
        // A locked app (a revoked GitHub or Drive, say) starts no new work. Nobody is watching a
        // scheduled run, so the owner hears why nothing ran.
        (refusal(task) ?: ports.lockNow()?.let(::lockedText))?.let { why ->
            ports.notify(task.id, "Scheduled task did not run", "${task.title}: $why")
            throw ScheduleException(why)
        }
        ports.heavyWorkRefusal()?.let { throw ScheduleException(it) }
        return if (existingSessionId == null) newSession(task) else shownSession(task, existingSessionId)
    }

    /** "Run now" already showed the owner its session: the task runs there or not at all. */
    private suspend fun shownSession(task: ScheduledTask, sessionId: String): SessionRecord = ports.session(sessionId) ?: run {
        ports.notify(task.id, "Scheduled task needs a look", "${task.title}: $SESSION_GONE")
        throw ScheduleException(SESSION_GONE)
    }

    private fun endedText(timedOut: Boolean, background: Boolean, exitCode: Int?, ok: Boolean): String = when {
        timedOut && background -> BACKGROUND_STOP
        timedOut -> "Stopped after ${HeadlessCommand.TIME_LIMIT_MINUTES} minutes, the time limit for a scheduled task."
        exitCode == null -> CUT_OFF
        ok -> "Finished."
        else -> "Ended with code $exitCode."
    }

    /**
     * Ends the run of [task] that was cut off with no chance to say so (Android stopped the app
     * with it), in that run's own session. Nothing starts again: Android would cut a new run off
     * the same way, and each start would be another session and another agent run.
     */
    suspend fun endCutOff(task: ScheduledTask) {
        val sessionId = task.runningSessionId ?: return
        val startedAt = task.runningSince ?: ports.clock.now()
        withContext(NonCancellable) {
            if (ports.session(sessionId) == null) {
                ports.recordRun(task.id, startedAt, sessionId)
            } else {
                finish(task, Ending(startedAt, sessionId, CUT_OFF))
            }
        }
    }

    /** Tells a "Run now" session that it did not run, because the task was running already. */
    suspend fun endAsBusy(task: ScheduledTask, sessionId: String) = withContext(NonCancellable) {
        save(task, Ending(ports.clock.now(), sessionId, BUSY))
        ports.notify(task.id, "Scheduled task needs a look", "${task.title}: $BUSY")
    }

    /**
     * Why [task] may never run unattended, or null. Someone else's code could steer an agent
     * that runs with nobody watching, with edits accepted and the room's tools on.
     */
    suspend fun refusal(task: ScheduledTask): String? = when {
        task.agentId !in HeadlessCommand.supported -> NO_HEADLESS
        ports.someoneElses(task.projectId) -> SOMEONE_ELSES
        else -> null
    }

    private suspend fun finish(task: ScheduledTask, ending: Ending) {
        save(task, ending)
        ports.recordRun(task.id, ending.startedAt, ending.sessionId)
        ports.afterRun(ending.sessionId)
        val heading = if (ending.ok) "Scheduled task finished" else "Scheduled task needs a look"
        ports.notify(task.id, heading, "${task.title}: ${ending.text} Open the session to review it.")
    }

    private suspend fun save(task: ScheduledTask, ending: Ending) {
        val file = ports.scratchFile()
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { w ->
                w.appendLine("Scheduled task: ${task.title}")
                w.appendLine("Agent: ${task.agentId}   Started: ${Ist.dateTime(ending.startedAt)}")
                w.appendLine(ending.text)
                w.appendLine()
                ending.output.forEach(w::appendLine)
                if (ending.cut) w.appendLine("[Output cut at ${MAX_OUTPUT_BYTES / 1024} KB.]")
            }
            ports.saveOutput(ending.sessionId, file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The session and its commits still show what happened.
        } finally {
            file.delete()
        }
    }

    /** How a run ended, for its saved output and the notification. */
    private class Ending(
        val startedAt: Long,
        val sessionId: String,
        val text: String,
        val output: List<String> = emptyList(),
        val cut: Boolean = false,
        val ok: Boolean = false,
    )

    /** What the agent wrote, tokens hidden, up to [MAX_OUTPUT_BYTES]. */
    private class Output {
        private val lines = ArrayList<String>()
        private var bytes = 0L

        val cut: Boolean @Synchronized get() = bytes >= MAX_OUTPUT_BYTES

        @Synchronized
        fun add(line: String) {
            if (bytes >= MAX_OUTPUT_BYTES) return
            val clean = Redact.text(line)
            lines += clean
            bytes += clean.length + 1
        }

        @Synchronized
        fun lines(): List<String> = lines.toList()
    }

    companion object {
        const val MAX_OUTPUT_BYTES = 2L * 1024 * 1024
        const val SESSION_GONE = "The chat this task was started in is no longer on this phone. Run the task again."

        /** Android stops a job outside the foreground after ten minutes; the run ends first. */
        const val BACKGROUND_LIMIT_MINUTES = 9
        const val BACKGROUND_LIMIT_MS = BACKGROUND_LIMIT_MINUTES * 60_000L

        const val BACKGROUND_STOP = "Stopped after $BACKGROUND_LIMIT_MINUTES minutes: Android gives no more to a task that starts " +
            "while PocketIDE is in the background. To give tasks up to ${HeadlessCommand.TIME_LIMIT_MINUTES} minutes, let PocketIDE " +
            "use the battery without restrictions in Android's settings."
        const val CUT_OFF = "Stopped before it finished (Android ended the job, or the phone left the charger or Wi-Fi). " +
            "It was not started again; the next run is at its usual time."
        const val BUSY = "Not run: this task was already running. That run's session has its result."
        const val SOMEONE_ELSES = "Scheduled tasks run only on your own projects: someone else's code could steer an agent with nobody watching."
        const val NO_HEADLESS = "This agent has no command-line mode, so it cannot run scheduled tasks. Choose Claude Code, Codex or Antigravity."

        /** Why a scheduled task does not start while the app is locked, as the lock screen would say. */
        fun lockedText(lock: LockReason): String = when (lock) {
            LockReason.GitHubDisconnected -> "PocketIDE's access to your GitHub was removed. Open PocketIDE to reconnect it."
            LockReason.DriveDisconnected -> "PocketIDE's access to your Drive was removed. Open PocketIDE to reconnect it."
            is LockReason.OtherPhone -> "PocketIDE is in use on ${lock.deviceName}. Open it on this phone to use it here again."
            is LockReason.StorageFull -> "New work waits until there is space in Drive. Open PocketIDE to see how to make space."
            is LockReason.Unsupported -> lock.why
        }
    }
}
