package com.pocketide.sessions

import com.pocketide.model.SessionRecord
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface PutOnMainResult {
    data object Merged : PutOnMainResult
    /** Merge conflicts: the agent in that session resolves them, then the owner taps again. */
    data class Conflicts(val files: List<String>) : PutOnMainResult
    data class Blocked(val why: String) : PutOnMainResult
    data class Failed(val why: String) : PutOnMainResult
}

data class ChangedFile(val path: String, val added: Int, val removed: Int)

data class SessionChanges(val commits: List<String>, val files: List<ChangedFile>)

/**
 * One message of a read-only transcript view. [role] is "user", "assistant", "tool" (one line
 * per tool call) or "note" (PocketIDE explaining what cannot be shown here).
 */
data class TranscriptEntry(val role: String, val text: String, val at: Long?, val imageCount: Int = 0)

/** Something the owner can act on, in one plain sentence (the screens show [message] as it is). */
class SessionException(message: String) : Exception(message)

/**
 * Chat sessions. Each is a branch `pocket/<agent>/<yyyy-mm-dd>-<slug>` with its own worktree;
 * commits go to that branch only. Deleting moves it to Recently deleted (30 days, counted from
 * the date stored in Drive), then it is erased; "Delete forever" erases now.
 */
interface Sessions {
    val all: StateFlow<List<SessionRecord>>

    suspend fun start(projectId: String, agentId: String, title: String? = null): SessionRecord

    suspend fun rename(sessionId: String, title: String)

    /** Reopen in its agent (the room opens on its worktree and continues the agent's own session). */
    suspend fun continueSession(sessionId: String)

    suspend fun delete(sessionId: String)

    suspend fun restore(sessionId: String)

    suspend fun deleteForever(sessionId: String)

    /** Merges exactly this session's branch into the default branch, check-post, push. */
    suspend fun putOnMain(sessionId: String): PutOnMainResult

    suspend fun changes(sessionId: String): SessionChanges

    /** A read-only view of the agent's transcript for this session. */
    suspend fun transcript(sessionId: String): List<TranscriptEntry>

    /** Keeps this session on the phone only ("Don't back up this chat"). */
    suspend fun setBackUp(sessionId: String, backUp: Boolean)

    /** Removes media from a session but keeps the chat. */
    suspend fun removeMedia(sessionId: String)

    /**
     * Autosave: pushes the session's branch to GitHub through the check-post, so the code is
     * never only on the phone. Returns null when saved, or a plain reason it was not.
     */
    suspend fun autosave(sessionId: String): String?

    /** Rescans transcripts and worktrees (sizes, commits, tokens, last activity). */
    suspend fun refresh()

    /** The session currently open in an agent's room, if any. */
    fun activeSession(agentId: String): String?

    /**
     * True when the transcript the agent is writing for this session passed about 10 MB: an agent
     * may no longer be able to resume it, so the screen suggests starting a fresh session.
     * Known after [refresh].
     */
    fun largeTranscript(sessionId: String): Boolean = false

    /**
     * The agent's own files for this session on this phone (transcripts, and what the agent keeps
     * beside them), for sync. Media is not included: it lives in the session's media folder.
     */
    suspend fun transcriptFiles(sessionId: String): List<File> = emptyList()

    /**
     * Records from the vault index: a restore on a new phone, another phone's changes, conflict
     * copies. Unknown ids are added and known ones replaced, except sessions waiting to be erased.
     */
    suspend fun adopt(records: List<SessionRecord>) = Unit

    /** Called by the sync engine once these deleted sessions are erased from Drive: they go from here too. */
    suspend fun erased(sessionIds: List<String>) = Unit

    companion object {
        /**
         * The `deletedAt` of a session deleted forever: older than any 30-day window, so the sync
         * engine's next run erases it from Drive, then calls [erased]. Screens do not list it.
         */
        const val ERASE_NOW: Long = 0L
    }
}
