package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.model.SessionRecord
import com.pocketide.projects.BareRefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * A phone and a "GitHub" on this machine: a bare repository standing in for GitHub, the gate
 * fetching and pushing with host git, and Linux played by host git with the rooms' guest paths
 * mapped to their host folders. The session manager runs the same git commands as on the phone.
 */
internal class GitRig(root: File, isPrivate: Boolean = true) {
    val dirs: AppDirs = testDirs(root)
    val origin = File(root, "github/demo.git")
    val gate = LocalRemoteGitGate(origin)
    val computer = HostGitComputer(File(root, "scratch").apply { mkdirs() })
    val sync = FakeSync()
    val rooms = FakeRooms()
    val media = FakeMedia { sessionId -> File(dirs.work, "media-of-$sessionId") }
    val projects = FakeProjects(listOf(project(isPrivate))) { id ->
        val bare = dirs.bareRepo(id)
        if (BareRefs(bare).isCloned()) {
            gate.fetch(bare, "token")
        } else {
            dirs.repos.mkdirs()
            gate.clone(origin.absolutePath, bare, "token")
        }
    }
    val env = TestSessionEnv(projects, computer, gate, rooms = rooms, sync = sync, media = media)
    var now: Long = Instant.parse("2026-09-24T05:00:00Z").toEpochMilli()
    private val upstream = File(root, "upstream")

    init {
        origin.mkdirs()
        hostGit(origin, "init", "--quiet", "--bare", "-b", "main")
        upstream.mkdirs()
        hostGit(upstream, "init", "--quiet", "-b", "main")
        hostGit(upstream, "remote", "add", "origin", origin.absolutePath)
        writeUpstream("README.txt", "Demo\n", "First commit")
        writeUpstream("app/Settings.kt", "val theme = \"light\"\n", "Settings")
    }

    fun manager(scope: CoroutineScope, clock: Clock = Clock { now }) =
        SessionManager(env, dirs, clock, scope, Dispatchers.IO, branchDate = ::indianDate)

    /** Somebody else's commit, pushed to GitHub's main. */
    fun writeUpstream(path: String, text: String, message: String) {
        if (hasCommits(upstream)) hostGit(upstream, "pull", "--quiet", "--ff-only", "origin", "main")
        File(upstream, path).apply { parentFile?.mkdirs() }.writeText(text)
        hostGit(upstream, "add", "-A")
        hostGit(upstream, "commit", "--quiet", "-m", message)
        hostGit(upstream, "push", "--quiet", "origin", "main")
    }

    /** A branch someone pushed to GitHub, at GitHub's main. */
    fun writeUpstreamBranch(branch: String) {
        hostGit(upstream, "push", "--quiet", "origin", "main:refs/heads/$branch")
    }

    fun worktree(session: SessionRecord): File = dirs.worktree(session.agentId, session.projectId, session.id)

    /** What an agent does in its session: edit a file and commit. */
    fun agentCommits(session: SessionRecord, path: String, text: String, message: String = "Change $path") {
        val dir = worktree(session)
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(text)
        hostGit(dir, "add", "-A")
        hostGit(dir, "commit", "--quiet", "-m", message)
    }

    fun bare(): File = dirs.bareRepo(PROJECT_ID)

    fun originHas(branch: String): Boolean =
        hostGit(origin, "for-each-ref", "--format=%(refname)", "refs/heads/$branch").isNotEmpty()

    fun originFile(path: String): String = hostGit(origin, "show", "main:$path")

    fun localHas(ref: String): Boolean = hostGit(bare(), "for-each-ref", "--format=%(refname)", ref).isNotEmpty()

    private fun hasCommits(dir: File) = File(dir, ".git/refs/heads/main").isFile

    companion object {
        fun indianDate(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString()
    }
}
