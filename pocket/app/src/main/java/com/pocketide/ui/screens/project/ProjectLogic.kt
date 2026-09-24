package com.pocketide.ui.screens.project

import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.PutOnMainResult
import com.pocketide.ui.components.Tone
import java.time.Instant
import java.util.Locale

/** Plain wording for sizes and counts, shared by the work screens. */
object WorkFormat {
    private val units = listOf("KB", "MB", "GB", "TB")

    fun bytes(n: Long): String {
        if (n < 1024) return "${n.coerceAtLeast(0)} B"
        var value = n / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value < 10) String.format(Locale.ROOT, "%.1f", value) else value.toLong().toString()
        return "$text ${units[unit]}"
    }

    fun count(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"
}

/** When a transcript passes this, the agent may no longer resume it (§6.10). */
const val LARGE_TRANSCRIPT_BYTES: Long = 10L * 1024 * 1024

const val LARGE_TRANSCRIPT_WARNING = "This chat is getting big. Start a fresh session to keep it resumable."

fun isLargeTranscript(session: SessionRecord): Boolean = session.transcriptBytes >= LARGE_TRANSCRIPT_BYTES

/** Ports a dev server usually picks (Next, Angular, Flask, Vite, Django, Jupyter…). */
val COMMON_DEV_PORTS: List<Int> = listOf(3000, 3001, 4200, 5000, 5173, 8000, 8080, 8888)

/**
 * Ports Preview offers: those the agent announced for this session first (in its order), then
 * common dev ports found listening on the phone. Ports the app itself uses (the bridge's own
 * listeners, agent screens, terminals) are never offered.
 */
fun previewPorts(announced: List<Int>, probedOpen: Set<Int>, excluded: Set<Int>): List<Int> {
    val valid = { port: Int -> port in 1..65535 && port !in excluded }
    val fromAgent = announced.filter(valid).distinct()
    val found = COMMON_DEV_PORTS.filter { it in probedOpen && valid(it) && it !in fromAgent }
    return fromAgent + found
}

/** A project's live sessions grouped by agent; the most recently active group and session first. */
fun sessionsByAgent(sessions: List<SessionRecord>, projectId: String): List<Pair<String, List<SessionRecord>>> =
    sessions
        .filter { it.projectId == projectId && it.status != SessionStatus.DELETED }
        .groupBy { it.agentId }
        .map { (agent, list) -> agent to list.sortedByDescending { it.lastActivityAt } }
        .sortedByDescending { (_, list) -> list.first().lastActivityAt }

/** The session a tab opens on by default: the latest open one, else the latest of any. */
fun defaultSession(sessions: List<SessionRecord>): SessionRecord? {
    val live = sessions.filter { it.status != SessionStatus.DELETED }
    return live.filter { it.status == SessionStatus.OPEN }.maxByOrNull { it.lastActivityAt }
        ?: live.maxByOrNull { it.lastActivityAt }
}

/** The session whose branch a build ran on, so its results land in that session's Media. */
fun sessionForBranch(sessions: List<SessionRecord>, branch: String): SessionRecord? =
    sessions.filter { it.branch == branch && it.status != SessionStatus.DELETED }.maxByOrNull { it.lastActivityAt }

fun waitingVideosText(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 video waiting for Wi-Fi"
    else -> "$count videos waiting for Wi-Fi"
}

fun sessionStatusLabel(status: SessionStatus, running: Boolean): Pair<String, Tone> = when (status) {
    SessionStatus.OPEN -> if (running) "Running" to Tone.OK else "Open" to Tone.NEUTRAL
    SessionStatus.ON_MAIN -> "On main" to Tone.OK
    SessionStatus.CONFLICT_COPY -> "Conflict copy" to Tone.WARN
    SessionStatus.DELETED -> "Deleted" to Tone.ERROR
}

/** What the owner reads after "Put on main". */
data class Outcome(val title: String, val text: String, val tone: Tone)

fun describePutOnMain(result: PutOnMainResult): Outcome = when (result) {
    PutOnMainResult.Merged -> Outcome(
        "On main",
        "This session's work is now on main and saved to GitHub.",
        Tone.OK,
    )
    is PutOnMainResult.Conflicts -> Outcome(
        "Main changed too",
        buildString {
            append("These files changed on main and in this session: ")
            append(result.files.take(MAX_LISTED_FILES).joinToString(", "))
            if (result.files.size > MAX_LISTED_FILES) append(", and ${result.files.size - MAX_LISTED_FILES} more")
            append(". Open the session and ask the agent to resolve them, then tap Put on main again.")
        },
        Tone.WARN,
    )
    is PutOnMainResult.Blocked -> Outcome(
        "Stopped at the check-post",
        "${result.why.trimEnd('.')}. Nothing was pushed. Ask the agent to fix it in this session, then try again.",
        Tone.WARN,
    )
    is PutOnMainResult.Failed -> Outcome(
        "Not put on main",
        "${result.why.trimEnd('.')}. Nothing changed on main.",
        Tone.ERROR,
    )
}

private const val MAX_LISTED_FILES = 8

/** A GitHub Actions run's state in plain words. */
fun runStatus(run: WorkflowRun): Pair<String, Tone> = when (run.status) {
    "completed" -> when (run.conclusion) {
        "success" -> "Succeeded" to Tone.OK
        "failure", "startup_failure" -> "Failed" to Tone.ERROR
        "timed_out" -> "Timed out" to Tone.ERROR
        "cancelled" -> "Cancelled" to Tone.NEUTRAL
        "skipped" -> "Skipped" to Tone.NEUTRAL
        "action_required" -> "Needs you on GitHub" to Tone.WARN
        else -> "Finished" to Tone.NEUTRAL
    }
    "in_progress" -> "Running" to Tone.OK
    "queued", "pending", "requested", "waiting" -> "Waiting for a runner" to Tone.WARN
    else -> run.status.replaceFirstChar { it.uppercase() } to Tone.NEUTRAL
}

/** What the agent screen shows for its room. */
sealed interface RoomView {
    data class Opening(val step: String?) : RoomView
    data class Ready(val url: String) : RoomView
    data class Failed(val why: String) : RoomView
    /** The agent's room is open on a different session now. */
    data object Elsewhere : RoomView
    /** The room stopped after it had opened (idle close, the limiter, or Stop). */
    data object Stopped : RoomView
}

/**
 * Combines the answer of `rooms.open` ([opened], null while it runs) with the room's live state
 * ([live]), for the session [sessionId].
 */
fun roomView(opened: RoomState?, live: RoomState?, sessionId: String): RoomView {
    if (opened is RoomState.Failed) return RoomView.Failed(opened.why)
    return when (val current = live ?: opened) {
        null -> RoomView.Opening(null)
        is RoomState.Running -> when {
            current.sessionId == null || current.sessionId == sessionId -> RoomView.Ready(current.url)
            opened == null -> RoomView.Opening(null)
            else -> RoomView.Elsewhere
        }
        is RoomState.Starting -> RoomView.Opening(current.step)
        is RoomState.Failed -> if (opened == null) RoomView.Opening(null) else RoomView.Failed(current.why)
        RoomState.Stopped -> if (opened == null) RoomView.Opening(null) else RoomView.Stopped
    }
}

/** GitHub's ISO-8601 timestamps as epoch milliseconds, or null when unreadable. */
fun isoToEpoch(text: String?): Long? = text?.let {
    try {
        Instant.parse(it).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
