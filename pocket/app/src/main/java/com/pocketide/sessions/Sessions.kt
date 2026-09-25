package com.pocketide.sessions

import com.pocketide.git.Hold
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

sealed interface PutOnMainResult {
    data object Merged : PutOnMainResult
    /** Merge conflicts: the agent in that session resolves them, then the owner taps again. */
    data class Conflicts(val files: List<String>) : PutOnMainResult
    /**
     * The check-post stopped the push. [holds] are the check-post's holds: a workflow change is
     * shown with its diff for the owner to approve (`GitGate.approveWorkflowChange` with its
     * approval key, then Put on main again); a build output has no approval, the agent removes it.
     */
    data class Blocked(val why: String, val holds: List<Hold> = emptyList()) : PutOnMainResult
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
 * A session continued by another agent (or forked in the same one): [session] is new, branched
 * from the old session's last commit, and [note] is the hand-off to give its agent as the first
 * message (goal, files changed, last step, what is left).
 */
data class HandOff(val session: SessionRecord, val note: String)

/** A file the owner added to a session: where the agent finds it, and its size. */
data class AddedFile(val guestPath: String, val bytes: Long)

/**
 * Chat sessions. Each is a branch `pocket/<agent>/<yyyy-mm-dd>-<slug>` with its own worktree;
 * commits go to that branch only. Deleting moves it to Recently deleted (30 days, counted from
 * the date stored in Drive), then it is erased; "Delete forever" erases now.
 */
interface Sessions {
    /** What is known so far: empty until `vault/sessions.json` is read, so a job in a fresh process uses [loaded]. */
    val all: StateFlow<List<SessionRecord>>

    /**
     * The sessions once this phone's list, and which session each room has open, are read from
     * the disk. Background jobs in a process Android has just started read this, not [all].
     */
    suspend fun loaded(): List<SessionRecord> = all.value

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

    /**
     * Before the computer is reset: commits whatever the session's worktree holds that is not
     * committed yet (as the owner), then pushes the branch through the check-post now, without
     * waiting for the autosave window. Returns null when saved, or a plain reason it was not.
     */
    suspend fun saveNow(sessionId: String): String? = autosave(sessionId)

    /**
     * Before "Delete everything", which erases this phone's clones and worktrees: every open
     * session's work is saved as [saveNow] does, then what would still be lost is named, one plain
     * sentence per project (changes not committed, commits GitHub does not have). Empty when all
     * the code on this phone is in GitHub. The rooms should be stopped first.
     */
    suspend fun codeOnlyOnPhone(): List<String> = emptyList()

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

    /**
     * "Continue in Codex / Antigravity" when an agent hit its usage limit, or a fork in the same
     * agent: a new session in [toAgentId]'s room whose branch starts at this session's last commit.
     * This session stays as it is.
     */
    suspend fun handOff(sessionId: String, toAgentId: String): HandOff =
        throw SessionException("Handing a chat to another agent is not available.")

    /**
     * Renames the session's branch (`pocket/<agent>/<date>-<name>`). On a public repository new
     * branches get neutral names; this is how the owner chooses a telling one. Only a branch that
     * is not on GitHub yet can be renamed.
     */
    suspend fun renameBranch(sessionId: String, name: String) = Unit

    /**
     * Adds a file the owner picked (Android's file picker or "Share to PocketIDE"): into the
     * session's project folder when [intoProject], else to the session's media as an attachment.
     * [source] is read to the end and closed.
     */
    suspend fun addFile(sessionId: String, name: String, source: InputStream, intoProject: Boolean): AddedFile =
        throw SessionException("Adding files is not available.")

    companion object {
        /**
         * A `deletedAt` that means "erase now" (older than any 30-day window). This phone never
         * lists a record carrying it: such a session is erased from Drive, then dropped.
         */
        const val ERASE_NOW: Long = 0L
    }
}
