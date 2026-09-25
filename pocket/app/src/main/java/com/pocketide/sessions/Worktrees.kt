package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.projects.BareRefs
import com.pocketide.projects.BareRefs.Companion.HEADS
import com.pocketide.projects.BareRefs.Companion.ORIGIN
import com.pocketide.projects.SafeFiles
import java.io.File

/**
 * Session worktrees, made and removed inside Linux so git records the paths the rooms see. Every
 * session worktree is locked: another room, which cannot see this room's folders, must never
 * prune it as missing.
 */
internal class Worktrees(private val git: LinuxGit, private val dirs: AppDirs) {

    fun host(session: SessionRecord): File = dirs.worktree(session.agentId, session.projectId, session.id)

    fun exists(session: SessionRecord): Boolean = SafeFiles.isFile(File(host(session), ".git"))

    /** Makes a new session's worktree on the new [branch], from [start] (null: the repository is still empty). */
    suspend fun create(agentId: String, projectId: String, sessionId: String, branch: String, start: String?): LinuxGit.Output {
        val guest = AppDirs.guestWorktree(projectId, sessionId)
        val add = listOf("-C", AppDirs.guestBareRepo(projectId), "worktree", "add", "--lock")
        val how = if (start == null) listOf("--orphan", "-b", branch, guest) else listOf("--no-track", "-b", branch, guest, start)
        return git.run(agentId, add + how)
    }

    /**
     * Brings back the worktree of an existing session: on a new phone, for a chat continued after
     * Put on main, or when its folder went away. It checks out the session's branch from this
     * phone or GitHub; a branch that exists nowhere any more starts again from the default branch.
     */
    suspend fun restore(session: SessionRecord, project: Project) {
        if (exists(session)) return
        val folder = host(session)
        if (SafeFiles.children(folder).isNotEmpty()) {
            throw SessionException("This session's folder is no longer a git worktree. Start a new session instead.")
        }
        val bare = dirs.bareRepo(project.id)
        val refs = BareRefs(bare)
        // The folder went away but git still has it registered, and locked: that needs a double force.
        val force = if (SafeFiles.isDirectory(File(bare, "worktrees/${session.id}"))) listOf("--force", "--force") else emptyList()
        val guest = AppDirs.guestWorktree(project.id, session.id)
        val start = refs.firstOf(ORIGIN + project.defaultBranch, HEADS + project.defaultBranch)
        val how = when {
            refs.exists(HEADS + session.branch) -> listOf(guest, session.branch)
            refs.exists(ORIGIN + session.branch) -> listOf("--no-track", "-b", session.branch, guest, ORIGIN + session.branch)
            start != null -> listOf("--no-track", "-b", session.branch, guest, start)
            else -> listOf("--orphan", "-b", session.branch, guest)
        }
        val out = git.run(session.agentId, listOf("-C", AppDirs.guestBareRepo(project.id), "worktree", "add", "--lock") + force + how)
        if (!out.ok) throw SessionException("Could not open this session's folder: ${out.reason()}")
    }

    /**
     * Moves the session's branch up to GitHub's when another phone added commits to it (one phone
     * works at a time), so the agent never goes on from older code. Only forward, and only when
     * nothing on this phone is uncommitted or newer; anything else is left for the agent.
     */
    suspend fun catchUp(session: SessionRecord, project: Project) {
        val refs = BareRefs(dirs.bareRepo(project.id))
        val local = refs.sha(HEADS + session.branch) ?: return
        val remote = refs.sha(ORIGIN + session.branch) ?: return
        if (local == remote) return
        val bare = AppDirs.guestBareRepo(project.id)
        if (!git.run(null, listOf("-C", bare, "merge-base", "--is-ancestor", local, remote)).ok) return
        if (!exists(session)) {
            git.run(null, listOf("-C", bare, "update-ref", HEADS + session.branch, remote, local))
        } else if (isDirty(session) == false) {
            val guest = AppDirs.guestWorktree(project.id, session.id)
            git.run(session.agentId, listOf("-C", guest, "merge", "--ff-only", "--quiet", ORIGIN + session.branch))
        }
    }

    /** Whether the worktree has changes that are not committed; null when there is no worktree. */
    suspend fun isDirty(session: SessionRecord): Boolean? {
        if (!exists(session)) return null
        val out = git.run(session.agentId, listOf("-C", AppDirs.guestWorktree(session.projectId, session.id), "status", "--porcelain"))
        if (!out.ok) throw SessionException("Could not read this session's files: ${out.reason()}")
        return out.lines.any(::isChange)
    }

    /**
     * Removes a clean worktree (ignored files such as `node_modules` do not count). Returns false,
     * leaving it locked in place, when git refuses because something changed meanwhile.
     */
    suspend fun remove(session: SessionRecord): Boolean {
        if (!exists(session)) return true
        val bare = AppDirs.guestBareRepo(session.projectId)
        val guest = AppDirs.guestWorktree(session.projectId, session.id)
        git.run(session.agentId, listOf("-C", bare, "worktree", "unlock", guest))
        if (git.run(session.agentId, listOf("-C", bare, "worktree", "remove", guest)).ok) return true
        git.run(session.agentId, listOf("-C", bare, "worktree", "lock", guest))
        return false
    }

    /**
     * Removes the worktree of a chat erased for good, with whatever it held that was never
     * committed; the branch keeps every commit. A folder git no longer knows goes as well.
     */
    suspend fun discard(session: SessionRecord) {
        val folder = host(session)
        if (exists(session)) {
            val bare = AppDirs.guestBareRepo(session.projectId)
            val guest = AppDirs.guestWorktree(session.projectId, session.id)
            git.run(session.agentId, listOf("-C", bare, "worktree", "unlock", guest))
            git.run(session.agentId, listOf("-C", bare, "worktree", "remove", "--force", guest))
        }
        if (!SafeFiles.delete(folder)) throw SessionException("Could not delete this session's folder.")
    }

    companion object {
        /** One line of `git status --porcelain`: two status letters, a space, a path. */
        private val CHANGE = Regex("^[ MTADRCU?!]{2} .+")

        fun isChange(line: String) = CHANGE.matches(line)
    }
}
