package com.pocketide.sessions

import com.pocketide.model.SessionRecord
import kotlinx.coroutines.flow.StateFlow

sealed interface PutOnMainResult {
    data object Merged : PutOnMainResult
    /** Merge conflicts: the agent in that session resolves them, then the owner taps again. */
    data class Conflicts(val files: List<String>) : PutOnMainResult
    data class Blocked(val why: String) : PutOnMainResult
    data class Failed(val why: String) : PutOnMainResult
}

data class ChangedFile(val path: String, val added: Int, val removed: Int)

data class SessionChanges(val commits: List<String>, val files: List<ChangedFile>)

/** One message of a read-only transcript view. */
data class TranscriptEntry(val role: String, val text: String, val at: Long?, val imageCount: Int = 0)

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

    /** Rescans transcripts and worktrees (sizes, commits, tokens, last activity). */
    suspend fun refresh()

    /** The session currently open in an agent's room, if any. */
    fun activeSession(agentId: String): String?
}
