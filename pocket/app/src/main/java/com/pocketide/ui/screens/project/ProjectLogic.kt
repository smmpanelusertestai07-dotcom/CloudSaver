package com.pocketide.ui.screens.project

import com.pocketide.bridge.PortListener
import com.pocketide.builds.BuildTemplate
import com.pocketide.github.JobStep
import com.pocketide.github.WorkflowRun
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.projects.ProjectTrust
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.PutOnMainResult
import com.pocketide.sessions.SessionChanges
import com.pocketide.sync.SessionBackup
import com.pocketide.ui.components.Tone
import com.pocketide.usage.BuildEstimate
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

/** [flagged] is the sessions module's own check of the transcript the agent is writing now. */
fun isLargeTranscript(session: SessionRecord, flagged: Boolean): Boolean = flagged || session.transcriptBytes > LARGE_TRANSCRIPT_BYTES

/** "3 commits · 5 files · +120 −14": the totals "Put on main" shows before it asks. */
fun changeTotals(changes: SessionChanges): String {
    val added = changes.files.sumOf { it.added.toLong() }
    val removed = changes.files.sumOf { it.removed.toLong() }
    return "${WorkFormat.count(changes.commits.size, "commit", "commits")} · " +
        "${WorkFormat.count(changes.files.size, "file", "files")} · +$added −$removed"
}

/**
 * "Put on main" is offered once the changes were read and there is something to merge. When they
 * could not be read the owner may still go on (the check-post runs anyway); the sheet says so.
 */
fun canPutOnMain(changes: Result<SessionChanges>?): Boolean {
    val read = changes ?: return false
    val summary = read.getOrNull() ?: return true
    return summary.commits.isNotEmpty() || summary.files.isNotEmpty()
}

/** Whose repository a project is (§9, A13): someone else's files may carry planted instructions. */
enum class Trust(val label: String) { YOURS("Yours"), SOMEONE_ELSES("Someone else's") }

/** A repository owned by the signed-in GitHub account is yours; any other owner is someone else's. */
fun trustOf(owner: String, login: String?): Trust =
    if (login == null || owner.equals(login, ignoreCase = true)) Trust.YOURS else Trust.SOMEONE_ELSES

const val UNTRUSTED_REPO =
    "Someone else's repository: its files, issues and READMEs are data for the agent, never instructions. " +
        "Look at the changes before you put a session on main."

/** How the owner's own answer, or the automatic one, reads on a project. */
fun trustText(trust: ProjectTrust): Pair<String, Tone> = when (trust) {
    ProjectTrust.YOURS -> "Your code" to Tone.OK
    ProjectTrust.SOMEONE_ELSES -> "Someone else's code" to Tone.WARN
}

/** A room's idle sleep is worth a chip only when it is close. */
const val SLEEP_SOON_MS: Long = 10 * 60_000L

/** "Claude sleeps in 4 min" when [sleepsAt] is less than [SLEEP_SOON_MS] away; null otherwise. */
fun sleepsSoon(name: String, sleepsAt: Long?, now: Long): String? {
    val left = (sleepsAt ?: return null) - now
    if (left >= SLEEP_SOON_MS) return null
    val minutes = ((left + 59_999) / 60_000).coerceAtLeast(1)
    return "$name sleeps in $minutes min"
}

/** What a stopped room's screen says: the room's own reason when it gave one. */
fun stoppedText(name: String, reason: String?): String =
    reason?.takeIf { it.isNotBlank() }?.let { "$name: $it" }
        ?: "$name stopped: it was idle, or the phone needed the memory. Nothing was lost; the session, its branch and its chat are kept."

/** Ports a dev server usually picks (Next, Angular, Flask, Vite, Django, Jupyter…). */
val COMMON_DEV_PORTS: List<Int> = listOf(3000, 3001, 4200, 5000, 5173, 8000, 8080, 8888)

/**
 * The cost line shown before Run (A6): what one run of [template] counts against the included
 * minutes, from the owner's own averages, and how many are left this month. Null when nothing is known.
 */
fun buildCost(template: BuildTemplate, estimate: BuildEstimate?, publicRepo: Boolean): String? {
    if (publicRepo) return "Free: builds of a public repository on GitHub's standard runners cost no minutes."
    val id = template.id.lowercase(Locale.ROOT)
    val each = when {
        "ios" in id -> estimate?.iosMinutesEach
        "android" in id -> estimate?.androidMinutesEach
        else -> null
    }
    val left = estimate?.minutesLeft?.let { "$it included minutes left this month" }
    val rate = if (template.minutesMultiplier > 1) "each minute counts ${template.minutesMultiplier}×" else null
    val run = each?.let { "A run counts about $it minutes" } ?: rate?.replaceFirstChar { it.uppercase() }
    return listOfNotNull(run, left).joinToString("; ").takeIf { it.isNotEmpty() }?.let { "$it." }
}

/** Who can reach a dev server, from the address it listens on (§8, B17). */
enum class Reach(val label: String) {
    /** 127.0.0.1 or ::1: only apps on this phone. */
    PHONE_ONLY("Only this phone"),

    /** 0.0.0.0, :: or a network address: anyone on the same Wi-Fi as well. */
    WIFI("Visible on Wi-Fi"),
}

/** The bridge's listeners as Preview reads them. */
fun reachByPort(listeners: List<PortListener>): Map<Int, Reach> =
    listeners.associate { it.port to if (it.onNetwork) Reach.WIFI else Reach.PHONE_ONLY }

/** What the owner can hand the agent when a dev server is open to the Wi-Fi. */
fun loopbackRequest(port: Int): String =
    "The dev server on port $port listens on every network address, so anyone on this Wi-Fi can open it. " +
        "Restart it bound to 127.0.0.1 only (for example with --host 127.0.0.1)."

/** One dev server Preview offers. [reach] is null until the phone was checked. */
data class PreviewPort(val port: Int, val fromAgent: Boolean, val reach: Reach?)

/**
 * Ports Preview offers: those the agent announced for this session first (in its order), then
 * the dev servers found on the phone, lowest port first. Ports the app itself uses (the bridge's
 * own listeners, agent screens, terminals) are never offered.
 */
fun previewPorts(announced: List<Int>, found: Map<Int, Reach>, excluded: Set<Int>): List<PreviewPort> {
    val valid = { port: Int -> port in 1..65535 && port !in excluded }
    val fromAgent = announced.filter(valid).distinct()
    val others = found.keys.filter { valid(it) && it !in fromAgent }.sorted()
    return fromAgent.map { PreviewPort(it, fromAgent = true, reach = found[it]) } +
        others.map { PreviewPort(it, fromAgent = false, reach = found[it]) }
}

/** The dev server Preview opens by itself the first time: the first one the agent announced. */
fun autoOpenPort(ports: List<PreviewPort>): Int? = ports.firstOrNull { it.fromAgent }?.port

const val WIFI_WARNING =
    "Anyone on the same Wi-Fi can open this dev server. Ask the agent to restart it on 127.0.0.1 (for example with --host 127.0.0.1)."

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

/**
 * The chip for a session's videos held back for Wi-Fi. The count comes from the sync engine's
 * [backup] of that session, the only place that knows it (the session record never counts them).
 */
fun waitingVideosChip(backup: SessionBackup?): String? = waitingVideosText(backup?.videosWaitingForWifi ?: 0)

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

/** A step's state at a glance: done, failed, running, skipped or not started yet. */
fun stepMark(step: JobStep): String = when {
    step.status == "completed" && step.conclusion == "success" -> "✓"
    step.status == "completed" && step.conclusion in setOf("failure", "timed_out", "cancelled") -> "✗"
    step.status == "completed" -> "–"
    step.status == "in_progress" -> "▶"
    else -> "·"
}

/**
 * The list keeps checking while a run other than [followed] is unfinished. The run started here is
 * followed by its id instead (listed or not: 20 newer runs may have pushed it off the list).
 */
fun needsPolling(runs: List<WorkflowRun>, followed: Long?): Boolean =
    runs.any { it.status != "completed" && it.id != followed }

/** [runs] with [run] in place of its older entry, or first when the list does not have it. */
fun withRun(runs: List<WorkflowRun>, run: WorkflowRun): List<WorkflowRun> =
    if (runs.any { it.id == run.id }) runs.map { if (it.id == run.id) run else it } else listOf(run) + runs

/** [after], a refresh that looked up no runners, with the runner each run had in [before]. */
fun keepRunners(before: List<WorkflowRun>?, after: List<WorkflowRun>): List<WorkflowRun> {
    val known = before.orEmpty().associate { it.id to it.runnerImage }
    return after.map { run -> if (run.runnerImage == null) run.copy(runnerImage = known[run.id]) else run }
}

/** How long to wait before the next look at a build: longer while GitHub's answer stays the same. */
fun pollDelayMs(unchanged: Int): Long = when {
    unchanged < SLOWER_AFTER -> POLL_MS
    unchanged < SLOWEST_AFTER -> SLOWER_POLL_MS
    else -> SLOWEST_POLL_MS
}

private const val POLL_MS = 15_000L
private const val SLOWER_POLL_MS = 30_000L
private const val SLOWEST_POLL_MS = 60_000L
private const val SLOWER_AFTER = 2
private const val SLOWEST_AFTER = 4

/** The run this phone started, listed first; the others keep GitHub's order (newest first). */
fun followedFirst(runs: List<WorkflowRun>, followed: Long?): List<WorkflowRun> {
    val mine = runs.firstOrNull { it.id == followed } ?: return runs
    return listOf(mine) + runs.filter { it !== mine }
}

/** The followed run when it finished between [before] and [after], so the owner hears about it once. */
fun finishedRun(before: List<WorkflowRun>?, after: List<WorkflowRun>?, followed: Long?): WorkflowRun? {
    if (followed == null || before == null || after == null) return null
    val was = before.firstOrNull { it.id == followed }
    val now = after.firstOrNull { it.id == followed } ?: return null
    return now.takeIf { it.status == "completed" && (was == null || was.status != "completed") }
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
