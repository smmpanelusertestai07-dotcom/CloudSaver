package com.pocketide.builds

import com.pocketide.core.AppDirs
import com.pocketide.core.Redact
import com.pocketide.linux.Bind
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.model.SessionRecord

/** Who a commit is by: the owner, with the address GitHub keeps private. */
data class GitIdentity(val name: String, val email: String)

/**
 * Commits one file of a session's worktree inside Linux, with the same layout and safety flags
 * the sessions use: the bare clones at /repos, the room's worktrees at /work, no hooks, no
 * system or global configuration, no signing. Nothing here uses the network.
 */
internal class WorktreeCommitter(private val computer: () -> Computer, private val dirs: AppDirs) {

    suspend fun commit(session: SessionRecord, path: String, message: String, identity: GitIdentity) {
        val worktree = AppDirs.guestWorktree(session.projectId, session.id)
        val status = git(session, identity, "-C", worktree, "status", "--porcelain", "--ignored", "--", path, errors = false)
        if (status.exit != 0) throw BuildsException(status.reason())
        // Already committed with this content: nothing to do.
        if (status.lines.none { it.isNotBlank() }) return
        val add = git(session, identity, "-C", worktree, "add", "--force", "--", path)
        if (add.exit != 0) throw BuildsException(add.reason())
        // Only this file: whatever the agent has staged stays staged, uncommitted.
        val commit = git(session, identity, "-C", worktree, "commit", "--no-verify", "-m", message, "--", path)
        if (commit.exit != 0) throw BuildsException(commit.reason())
    }

    private class Output(val exit: Int, val lines: List<String>) {
        fun reason(): String {
            val line = lines.lastOrNull { it.startsWith("fatal:") || it.startsWith("error:") } ?: lines.lastOrNull { it.isNotBlank() }
            val text = line?.removePrefix("fatal:")?.removePrefix("error:")?.trim()?.take(MAX_REASON)
            return if (text.isNullOrEmpty()) "git stopped with code $exit." else "git: ${Redact.text(text)}"
        }
    }

    /** With [errors] false only standard output is kept, for output that is parsed. */
    private suspend fun git(session: SessionRecord, identity: GitIdentity, vararg args: String, errors: Boolean = true): Output {
        val linux = computer()
        when (val state = linux.state.value) {
            ComputerState.Ready, is ComputerState.Updating -> Unit
            is ComputerState.Broken -> throw BuildsException("${state.why} ${state.fix}")
            else -> throw BuildsException("Set up the computer first.")
        }
        val binds = listOf(
            Bind(dirs.repos.absolutePath, AppDirs.GUEST_REPOS),
            Bind(dirs.roomWork(session.agentId).absolutePath, AppDirs.GUEST_WORK),
        )
        val env = mapOf(
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_OPTIONAL_LOCKS" to "0",
            "LC_ALL" to "C",
            "GIT_AUTHOR_NAME" to identity.name,
            "GIT_AUTHOR_EMAIL" to identity.email,
            "GIT_COMMITTER_NAME" to identity.name,
            "GIT_COMMITTER_EMAIL" to identity.email,
        )
        val lines = ArrayList<String>()
        val exit = linux.run(LinuxCommand(SAFE_GIT + args, binds, env, workDir = "/", mergeErrors = errors)) { line ->
            synchronized(lines) { if (lines.size < MAX_LINES) lines += line }
        }
        return Output(exit, synchronized(lines) { lines.toList() })
    }

    private companion object {
        const val MAX_LINES = 500
        const val MAX_REASON = 200
        val SAFE_GIT = listOf(
            "git",
            "-c", "core.hooksPath=/dev/null",
            "-c", "core.fsmonitor=false",
            "-c", "commit.gpgSign=false",
            "-c", "core.quotePath=false",
            "-c", "safe.directory=*",
        )
    }
}
