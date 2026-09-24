package com.pocketide.ui.manage

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.schedule.ScheduledTask
import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import java.util.Locale

/** Names of Variables and Secrets: valid environment names that GitHub also accepts. */
object ValueNames {
    const val MAX_LENGTH = 100
    private val shape = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Set by the computer itself for every room, so a Variable must not replace them. */
    private val reserved = setOf("HOME", "PATH", "LANG", "TERM", "TZ", "TMPDIR", "PWD", "SHELL", "USER", "LD_PRELOAD", "LD_LIBRARY_PATH")

    fun problem(name: String, kind: SecretKind): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Enter a name."
            n.length > MAX_LENGTH -> "Keep the name under $MAX_LENGTH characters."
            !shape.matches(n) -> "Use letters, digits and _ only, and do not start with a digit."
            kind == SecretKind.SECRET && n.uppercase(Locale.ROOT).startsWith("GITHUB_") ->
                "GitHub does not allow Secret names that start with GITHUB_."
            kind == SecretKind.VARIABLE && n.uppercase(Locale.ROOT) in reserved ->
                "The computer sets $n itself. Choose another name."
            else -> null
        }
    }

    /** Names are compared like GitHub compares secret names: without case. */
    fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /** The values one screen shows: a project's own set, or the global set when [projectId] is null. */
    fun scoped(values: List<ProjectValue>, projectId: String?): List<ProjectValue> =
        values.filter { it.projectId == projectId }.sortedWith(compareBy({ it.kind }, { it.name.lowercase(Locale.ROOT) }))
}

object ScheduleForm {
    const val MIN_HOURS = 1
    const val MAX_HOURS = 24 * 30
    const val MAX_TITLE = 80

    fun problem(title: String, prompt: String, everyHours: Int?, projectId: String?, agentId: String?): String? = when {
        projectId.isNullOrBlank() -> "Choose a project."
        agentId.isNullOrBlank() -> "Choose an agent."
        title.isBlank() -> "Give the task a short title."
        title.trim().length > MAX_TITLE -> "Keep the title under $MAX_TITLE characters."
        prompt.isBlank() -> "Write what the agent should do."
        everyHours == null || everyHours !in MIN_HOURS..MAX_HOURS -> "Choose how often: every 1 to $MAX_HOURS hours."
        else -> null
    }

    /** When the task is next due; the run itself still waits for charging on Wi-Fi. */
    fun nextDueAt(task: ScheduledTask, nowMs: Long): Long? {
        if (!task.enabled) return null
        val last = task.lastRunAt ?: return nowMs
        return last + task.everyHours * 3_600_000L
    }
}

/** Numbers for the Your data screen. */
object DataMath {
    fun sizeOf(session: SessionRecord): Long = session.transcriptBytes + session.mediaBytes

    /** Sessions that are not in Recently deleted, largest first. */
    fun largestSessions(sessions: List<SessionRecord>, limit: Int = 10): List<SessionRecord> =
        sessions.asSequence()
            .filter { it.status != SessionStatus.DELETED && sizeOf(it) > 0 }
            .sortedByDescending { sizeOf(it) }
            .take(limit)
            .toList()

    fun liveSessions(sessions: List<SessionRecord>) = sessions.filter { it.status != SessionStatus.DELETED }

    fun chatBytes(sessions: List<SessionRecord>): Long = liveSessions(sessions).sumOf { it.transcriptBytes }

    fun mediaBytes(sessions: List<SessionRecord>): Long = liveSessions(sessions).sumOf { it.mediaBytes }

    fun mediaCount(sessions: List<SessionRecord>): Int = liveSessions(sessions).sumOf { it.mediaCount }

    /** "Delete everything" runs only when the owner typed the word exactly. */
    fun deleteConfirmed(typed: String) = typed.trim() == "DELETE"
}
