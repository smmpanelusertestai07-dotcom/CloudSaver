package com.pocketide.model

import kotlinx.serialization.Serializable

/** A GitHub repository the owner works on. Code lives on GitHub; the phone holds a clone. */
@Serializable
data class Project(
    /** "<owner>/<repo>", lower case. Stable across phones. */
    val id: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String = "main",
    val isPrivate: Boolean = true,
    val addedAt: Long,
    /** Agent work or a commit, never merely opening the app (drives the 30-day cache rule). */
    val lastActivityAt: Long,
    /** Whether the bare clone exists on this phone. */
    val cloned: Boolean = false,
    /**
     * The owner's answer to "Is this your code?" (a ProjectTrust name), so it travels to a new
     * phone; null when not answered, and then worked out from the repository owner.
     */
    val trust: String? = null,
)

@Serializable
enum class SessionStatus { OPEN, ON_MAIN, DELETED, CONFLICT_COPY }

/**
 * One chat session. Every session has its own branch and worktree, so "Put this chat on main"
 * moves exactly that session's work. Times are UTC epoch milliseconds; screens show IST.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val agentId: String,
    val projectId: String,
    val title: String,
    /** pocket/<agent>/<yyyy-mm-dd>-<slug> */
    val branch: String,
    val startedAt: Long,
    val lastActivityAt: Long,
    val status: SessionStatus = SessionStatus.OPEN,
    /** Set when moved to Recently deleted; stored in Drive so a reinstall does not restart it. */
    val deletedAt: Long? = null,
    val commits: Int = 0,
    val filesChanged: Int = 0,
    val transcriptBytes: Long = 0,
    val mediaBytes: Long = 0,
    val mediaCount: Int = 0,
    /** False for "Don't back up this chat": kept on this phone only, marked "not backed up". */
    val backUp: Boolean = true,
    /**
     * Bytes of this session not yet confirmed by Drive, as last written here; never stored in
     * Drive. The live figure is the sync engine's `backups` (and `queueNow` before a phone copy goes).
     */
    val pendingBytes: Long = 0,
    /** Videos waiting for Wi-Fi. */
    val pendingVideos: Int = 0,
    /** For a conflict copy: the session it diverged from. */
    val conflictOf: String? = null,
    /** The phone that created it. */
    val deviceId: String,
    /** The agent's own session reference (Claude session id, Codex rollout file), to continue it. */
    val agentSessionRef: String? = null,
    /** Tokens used, where the agent records them in its transcript (Claude, Codex). */
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
)
