package com.pocketide.git

import java.io.File

enum class FindingKind { SECRET, AI_DATA, TOO_LARGE, VARIABLE_OR_SECRET_VALUE }

data class Finding(val kind: FindingKind, val path: String, val commit: String, val detail: String)

data class Verdict(val ok: Boolean, val findings: List<Finding>, val commitsScanned: Int)

sealed interface PushResult {
    data object Pushed : PushResult
    data class Blocked(val verdict: Verdict) : PushResult
    data class Rejected(val why: String) : PushResult
    data class Failed(val why: String) : PushResult
}

/**
 * Every network git operation happens on the Android side with JGit, so the GitHub token never
 * enters Linux. Before any push, the check-post scans every new commit for secrets, AI data
 * (.claude, .codex, .gemini, transcripts), the project's Variables and Secrets, and files over
 * 100 MB; one finding blocks the push.
 */
interface GitGate {
    /** Bare clone into [bareRepo] with remote-tracking refs. */
    suspend fun clone(cloneUrl: String, bareRepo: File, token: String)

    suspend fun fetch(bareRepo: File, token: String)

    /** Commits on [branch] not yet on the remote, checked. [knownValues] are Variables/Secrets. */
    suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String> = emptyList()): Verdict

    /** Check-post, then push [branch] to the same name on origin. */
    suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String> = emptyList()): PushResult

    /** Commits and changed files on [branch] compared with [base], for session details. */
    suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int>
}
