package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.git.PushResult
import com.pocketide.github.GitHubAccount
import com.pocketide.github.NotConnectedException
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.projects.BareRefs
import com.pocketide.projects.BareRefs.Companion.HEADS
import com.pocketide.projects.BareRefs.Companion.ORIGIN
import com.pocketide.projects.SafeFiles
import com.pocketide.sessions.transcripts.oneLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Put this chat on main": merges exactly one session's branch into the project's default branch
 * and pushes it through the check-post.
 *
 * The merge runs inside Linux in a temporary worktree of the default branch, so the session's own
 * worktree is never touched and a conflict leaves nothing behind. When the push is refused the
 * local default branch goes back to GitHub's. After a merge the session loses its worktree and
 * branch, unless work arrived meanwhile: then it stays open and the next Put on main takes the
 * rest. A session branch that was pushed (autosave) is deleted on GitHub too, once everything on
 * it is on main.
 */
internal class MainMerger(
    private val env: SessionEnv,
    private val git: LinuxGit,
    private val worktrees: Worktrees,
    private val dirs: AppDirs,
    private val io: CoroutineDispatcher,
) {
    /** What happened, and the status the session has now. */
    class Outcome(val result: PutOnMainResult, val status: SessionStatus)

    private sealed interface Merge {
        class Done(val previousMain: String?) : Merge
        class Conflicted(val files: List<String>) : Merge
        class Failed(val why: String) : Merge
    }

    suspend fun run(session: SessionRecord, project: Project): Outcome {
        fun failed(why: String) = Outcome(PutOnMainResult.Failed(why), session.status)

        if (worktrees.isDirty(session) == true) return failed(COMMIT_FIRST)
        val account = env.gitHubAuth.account.value ?: return failed(CONNECT_GITHUB)
        val token = try {
            env.gitHubAuth.token()
        } catch (signedOut: NotConnectedException) {
            return failed(CONNECT_GITHUB)
        }
        val bare = dirs.bareRepo(project.id)
        try {
            env.git.fetch(bare, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (offline: Exception) {
            return failed(OFFLINE)
        }
        val refs = BareRefs(bare)
        val branch = refs.firstOf(HEADS + session.branch, ORIGIN + session.branch) ?: return failed(NO_BRANCH)
        val base = refs.firstOf(ORIGIN + project.defaultBranch, HEADS + project.defaultBranch)
            ?: return failed("The ${project.defaultBranch} branch was not found. Check the repository on GitHub.")
        val tip = revParse(project, branch) ?: return failed(NO_BRANCH)
        if (commitsAhead(project, base, tip) == 0) return failed(NOTHING_NEW)

        val previousMain = when (val merge = merge(session, project, base, tip, identity(account))) {
            is Merge.Conflicted -> return Outcome(PutOnMainResult.Conflicts(merge.files), session.status)
            is Merge.Failed -> return failed(merge.why)
            is Merge.Done -> merge.previousMain
        }
        val pushed = try {
            env.git.push(bare, project.defaultBranch, token, env.secrets.allValues())
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { resetMain(project, previousMain) }
            throw cancelled
        } catch (failure: Exception) {
            PushResult.Failed(failure.message ?: OFFLINE)
        }
        if (pushed != PushResult.Pushed) {
            resetMain(project, previousMain)
            return Outcome(refused(pushed, project), session.status)
        }
        val done = try {
            cleanUp(session, project, tip)
        } catch (kept: SessionException) {
            false
        }
        if (done) deleteRemoteBranch(session, project, token)
        return Outcome(PutOnMainResult.Merged, if (done) SessionStatus.ON_MAIN else SessionStatus.OPEN)
    }

    /**
     * Deletes the session's branch on GitHub when all of it is on the new main. A branch that got
     * more commits elsewhere (another phone) stays. A failure is harmless: the branch's commits
     * are on main, and the owner can delete it on GitHub.
     */
    private suspend fun deleteRemoteBranch(session: SessionRecord, project: Project, token: String) {
        val remote = ORIGIN + session.branch
        if (!BareRefs(dirs.bareRepo(project.id)).exists(remote)) return
        try {
            val bare = AppDirs.guestBareRepo(project.id)
            val merged = git.run(null, listOf("-C", bare, "merge-base", "--is-ancestor", remote, HEADS + project.defaultBranch)).ok
            // The gate also drops its remote-tracking copy, so a chat continued later starts from main.
            if (merged) env.git.deleteRemoteBranch(dirs.bareRepo(project.id), session.branch, token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (kept: Exception) {
            // See above: the branch simply stays on GitHub.
        }
    }

    private suspend fun merge(session: SessionRecord, project: Project, base: String, tip: String, identity: LinuxGit.Identity): Merge {
        val room = session.agentId
        val bare = AppDirs.guestBareRepo(project.id)
        val name = "${AppDirs.projectDirName(project.id)}/.merge-${session.id}"
        val temp = "${AppDirs.GUEST_WORK}/$name"
        val tempHost = File(dirs.roomWork(room), name)
        try {
            withContext(io) { SafeFiles.delete(tempHost) }
            val add = git.run(room, listOf("-C", bare, "worktree", "add", "--force", "--detach", temp, base))
            if (!add.ok) return Merge.Failed("Could not prepare the merge: ${add.reason()}")
            val merged = git.run(room, listOf("-C", temp, "merge", "--no-ff", "--no-edit", "-m", message(session), tip), identity)
            if (!merged.ok) {
                val conflicted = git.run(room, listOf("-C", temp, "diff", "--name-only", "--diff-filter=U"), errors = false)
                    .lines.filter { it.isNotBlank() }
                git.run(room, listOf("-C", temp, "merge", "--abort"))
                return if (conflicted.isNotEmpty()) Merge.Conflicted(conflicted) else Merge.Failed("Could not merge: ${merged.reason()}")
            }
            val previousMain = revParse(project, HEADS + project.defaultBranch)
            val moved = git.run(room, listOf("-C", temp, "update-ref", HEADS + project.defaultBranch, "HEAD"))
            if (!moved.ok) return Merge.Failed("Could not update ${project.defaultBranch}: ${moved.reason()}")
            return Merge.Done(previousMain)
        } finally {
            withContext(NonCancellable) { removeTemporary(room, bare, temp, tempHost) }
        }
    }

    private suspend fun removeTemporary(room: String, bare: String, temp: String, tempHost: File) {
        try {
            git.run(room, listOf("-C", bare, "worktree", "remove", "--force", temp))
        } catch (unavailable: SessionException) {
            // The folder is deleted below; git's leftover note is replaced by the next forced add.
        }
        withContext(io) { SafeFiles.delete(tempHost) }
    }

    /**
     * The local default branch goes back to GitHub's (or to where it was, for a repository without
     * it). If even that fails, the next Put on main starts from GitHub's branch again anyway.
     */
    private suspend fun resetMain(project: Project, previousMain: String?) {
        val bare = AppDirs.guestBareRepo(project.id)
        val main = HEADS + project.defaultBranch
        try {
            val target = BareRefs(dirs.bareRepo(project.id)).firstOf(ORIGIN + project.defaultBranch)
                ?.let { revParse(project, it) } ?: previousMain
            val args = if (target != null) listOf("-C", bare, "update-ref", main, target) else listOf("-C", bare, "update-ref", "-d", main)
            git.run(null, args)
        } catch (unavailable: SessionException) {
            // See above: nothing is lost, the merge result simply stays local until replaced.
        }
    }

    /**
     * Removes the session's worktree and branch once everything on them is on main. Returns false
     * when work arrived after the merge (uncommitted changes, or new commits on the branch).
     */
    private suspend fun cleanUp(session: SessionRecord, project: Project, mergedTip: String): Boolean {
        if (worktrees.exists(session)) {
            if (worktrees.isDirty(session) != false) return false
            if (!worktrees.remove(session)) return false
        }
        val branch = HEADS + session.branch
        if (!BareRefs(dirs.bareRepo(project.id)).exists(branch)) return true
        // Deletes the branch only if it still points at what was merged: an atomic compare-and-delete.
        return git.run(null, listOf("-C", AppDirs.guestBareRepo(project.id), "update-ref", "-d", branch, mergedTip)).ok
    }

    private fun refused(pushed: PushResult, project: Project): PutOnMainResult = when (pushed) {
        is PushResult.Blocked -> PutOnMainResult.Blocked(CheckPostWords.blocked(pushed.verdict), pushed.verdict.holds)
        is PushResult.Rejected -> PutOnMainResult.Failed(
            "GitHub did not accept the new ${project.defaultBranch}: ${pushed.why} Tap Put on main again.",
        )
        is PushResult.Failed -> PutOnMainResult.Failed(pushed.why)
        PushResult.Pushed -> PutOnMainResult.Merged
    }

    private suspend fun revParse(project: Project, ref: String): String? {
        val out = git.run(null, listOf("-C", AppDirs.guestBareRepo(project.id), "rev-parse", "--verify", "--quiet", "$ref^{commit}"), errors = false)
        return out.lines.firstOrNull()?.trim()?.takeIf { out.ok && SHA.matches(it) }
    }

    private suspend fun commitsAhead(project: Project, base: String, tip: String): Int {
        val out = git.run(null, listOf("-C", AppDirs.guestBareRepo(project.id), "rev-list", "--count", "$base..$tip"), errors = false)
        if (!out.ok) throw SessionException("Could not compare this session with ${project.defaultBranch}: ${out.reason()}")
        return out.lines.firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    private fun message(session: SessionRecord) = "Merge session: ${oneLine(session.title, TITLE_CHARS)}"

    private fun identity(account: GitHubAccount) = LinuxGit.Identity(
        name = account.name?.takeIf { it.isNotBlank() } ?: account.login,
        email = "${account.id}+${account.login}@users.noreply.github.com",
    )

    private companion object {
        const val TITLE_CHARS = 60
        val SHA = Regex("[0-9a-f]{40,64}")
        const val COMMIT_FIRST = "This session has changes that are not committed. Ask the agent to commit its work first."
        const val CONNECT_GITHUB = "Connect GitHub first."
        const val OFFLINE = "Could not reach GitHub. Check the connection and try again."
        const val NO_BRANCH = "This session's branch is not on this phone or on GitHub."
        const val NOTHING_NEW = "There is nothing new to put on main: this session has no commits of its own yet."
    }
}
