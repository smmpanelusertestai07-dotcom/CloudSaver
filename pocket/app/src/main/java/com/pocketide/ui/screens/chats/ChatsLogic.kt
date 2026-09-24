package com.pocketide.ui.screens.chats

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.sessions.Sessions
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.project.waitingVideosText

enum class StatusFilter(val label: String) {
    ALL("All"),
    OPEN("Open"),
    ON_MAIN("On main"),
    CONFLICT("Conflict copies"),
}

/** The Chats list's search and filters. Null agent or project means any. */
data class ChatFilter(
    val query: String = "",
    val agentId: String? = null,
    val projectId: String? = null,
    val status: StatusFilter = StatusFilter.ALL,
)

/**
 * Sessions matching [filter], newest activity first. Deleted sessions live in Recently deleted,
 * never here. The search looks at the title, branch, project and agent name.
 */
fun filterChats(sessions: List<SessionRecord>, filter: ChatFilter, agentName: (String) -> String): List<SessionRecord> {
    val q = filter.query.trim().lowercase()
    return sessions
        .asSequence()
        .filter { it.status != SessionStatus.DELETED && it.deletedAt == null }
        .filter { filter.agentId == null || it.agentId == filter.agentId }
        .filter { filter.projectId == null || it.projectId == filter.projectId }
        .filter { matchesStatus(it.status, filter.status) }
        .filter {
            q.isEmpty() || listOf(it.title, it.branch, it.projectId, agentName(it.agentId)).any { field -> field.lowercase().contains(q) }
        }
        .sortedByDescending { it.lastActivityAt }
        .toList()
}

private fun matchesStatus(status: SessionStatus, filter: StatusFilter): Boolean = when (filter) {
    StatusFilter.ALL -> true
    StatusFilter.OPEN -> status == SessionStatus.OPEN
    StatusFilter.ON_MAIN -> status == SessionStatus.ON_MAIN
    StatusFilter.CONFLICT -> status == SessionStatus.CONFLICT_COPY
}

const val RECENTLY_DELETED_DAYS = 30
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Whole days until a deleted chat is erased for good (30 days from [deletedAt]); never negative. */
fun daysLeft(deletedAt: Long, now: Long): Int {
    val remaining = deletedAt + RECENTLY_DELETED_DAYS * DAY_MS - now
    if (remaining <= 0) return 0
    return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
}

fun daysLeftText(days: Int): String = when {
    days <= 0 -> "Erased at the next clean-up"
    days == 1 -> "1 day left"
    else -> "$days days left"
}

/**
 * Chats in Recently deleted, most recently deleted first. A chat deleted forever only waits for
 * the sync engine to erase it from Drive, so it is no longer listed.
 */
fun recentlyDeleted(sessions: List<SessionRecord>): List<SessionRecord> =
    sessions.filter { (it.status == SessionStatus.DELETED || it.deletedAt != null) && it.deletedAt != Sessions.ERASE_NOW }
        .sortedByDescending { it.deletedAt ?: it.lastActivityAt }

/** Where a chat stands with Drive, in words, and whether it needs the owner's eye. */
fun backupState(session: SessionRecord): Pair<String, Tone> = when {
    !session.backUp -> "Not backed up" to Tone.WARN
    session.pendingVideos > 0 -> (waitingVideosText(session.pendingVideos) ?: "Waiting for Wi-Fi") to Tone.WARN
    session.pendingBytes > 0 -> "Waiting to upload" to Tone.WARN
    else -> "Backed up" to Tone.OK
}

/** A chat's size: its transcript and its media. */
fun sessionBytes(session: SessionRecord): Long = session.transcriptBytes + session.mediaBytes

/** Only a chat whose branch still exists can be reopened in its agent (merged ones are done). */
fun SessionRecord.canContinue(): Boolean =
    deletedAt == null && (status == SessionStatus.OPEN || status == SessionStatus.CONFLICT_COPY)
