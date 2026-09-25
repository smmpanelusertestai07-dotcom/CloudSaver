package com.pocketide.sessions

import com.pocketide.git.Finding
import com.pocketide.git.FindingKind
import com.pocketide.git.Verdict
import com.pocketide.model.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Put on main" end to end: real git inside "Linux", a local stand-in for GitHub, a scripted check-post. */
class PutOnMainTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var rig: GitRig

    @Before
    fun setUp() {
        assumeTrue("git is needed on this machine", HostGitComputer.available)
        rig = GitRig(temp.root)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun mergeFolders() =
        File(rig.dirs.roomWork("claude"), "alice__demo").listFiles().orEmpty().filter { it.name.startsWith(".merge-") }

    @Test
    fun `merges exactly this session, pushes main and cleans the session up`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Dark theme")
        val other = sessions.start(PROJECT_ID, "claude", "Something else")
        rig.agentCommits(session, "app/Settings.kt", "val theme = \"dark\"\n")
        rig.agentCommits(other, "app/Other.kt", "class Other\n")
        assertEquals(null, sessions.autosave(session.id))
        assertTrue(rig.originHas(session.branch))
        rig.writeUpstream("CHANGELOG.txt", "Someone else's change\n", "Changelog")

        val result = sessions.putOnMain(session.id)

        assertEquals(PutOnMainResult.Merged, result)
        assertEquals("val theme = \"dark\"", rig.originFile("app/Settings.kt"))
        assertEquals("Someone else's change", rig.originFile("CHANGELOG.txt"))
        assertFalse("the other session's work stays on its branch", hostGit(rig.origin, "ls-tree", "-r", "--name-only", "main").contains("app/Other.kt"))
        assertEquals("Merge session: Dark theme", hostGit(rig.origin, "log", "-1", "--format=%s", "main"))
        assertEquals("2", hostGit(rig.origin, "log", "-1", "--format=%P", "main").split(' ').size.toString())
        assertEquals("Alice Example <42+alice@users.noreply.github.com>", hostGit(rig.origin, "log", "-1", "--format=%an <%ae>", "main"))

        val merged = sessions.all.value.single { it.id == session.id }
        assertEquals(SessionStatus.ON_MAIN, merged.status)
        assertFalse("the worktree goes", rig.worktree(session).exists())
        assertFalse("the local branch goes", rig.localHas("refs/heads/${session.branch}"))
        assertFalse("the branch on GitHub goes", rig.originHas(session.branch))
        assertEquals(listOf(session.branch), rig.gate.deletedRemote)
        assertTrue(mergeFolders().isEmpty())
        assertTrue(rig.projects.touchedAt.containsKey(PROJECT_ID))
        assertEquals("the session's autosave, then main", listOf(session.branch, "main"), rig.gate.pushed)
    }

    @Test
    fun `a branch never pushed is only removed here`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Quick fix")
        rig.agentCommits(session, "fix.txt", "fixed\n")

        assertEquals(PutOnMainResult.Merged, sessions.putOnMain(session.id))

        assertEquals("fixed", rig.originFile("fix.txt"))
        assertTrue(rig.gate.deletedRemote.isEmpty())
    }

    @Test
    fun `conflicts are reported for the agent to resolve, and nothing moves`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Dark theme")
        rig.agentCommits(session, "app/Settings.kt", "val theme = \"dark\"\n")
        rig.writeUpstream("app/Settings.kt", "val theme = \"blue\"\n", "Blue theme")
        val mainBefore = hostGit(rig.origin, "rev-parse", "main")

        val result = sessions.putOnMain(session.id)

        assertEquals(PutOnMainResult.Conflicts(listOf("app/Settings.kt")), result)
        assertEquals(mainBefore, hostGit(rig.origin, "rev-parse", "main"))
        assertEquals(listOf<String>(), rig.gate.pushed)
        assertEquals(SessionStatus.OPEN, sessions.all.value.single().status)
        assertTrue(File(rig.worktree(session), ".git").isFile)
        assertTrue("the temporary merge worktree is always removed", mergeFolders().isEmpty())
        assertFalse(hostGit(rig.bare(), "worktree", "list").contains(".merge-"))
    }

    @Test
    fun `a blocked push says why in plain words and puts the local main back`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Config")
        rig.agentCommits(session, "config.env", "TOKEN=ghp_example\n")
        val localMainBefore = hostGit(rig.bare(), "rev-parse", "refs/remotes/origin/main")
        rig.gate.blockNextPush = Verdict(false, listOf(Finding(FindingKind.SECRET, "config.env", "abc1234", "GitHub token")), 1)

        val result = sessions.putOnMain(session.id)

        assertTrue(result is PutOnMainResult.Blocked)
        val why = (result as PutOnMainResult.Blocked).why
        assertEquals(
            "The check-post stopped the push: a secret in config.env (GitHub token). Ask the agent to take them out of the commits, then try again.",
            why,
        )
        assertEquals(localMainBefore, hostGit(rig.bare(), "rev-parse", "refs/heads/main"))
        assertEquals(localMainBefore, hostGit(rig.origin, "rev-parse", "main"))
        assertEquals(SessionStatus.OPEN, sessions.all.value.single().status)
        assertTrue(rig.localHas("refs/heads/${session.branch}"))
        assertTrue(mergeFolders().isEmpty())
    }

    @Test
    fun `uncommitted work is refused before anything happens`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Draft")
        rig.agentCommits(session, "a.txt", "a\n")
        File(rig.worktree(session), "a.txt").writeText("changed but not committed\n")

        val result = sessions.putOnMain(session.id)

        assertTrue(result is PutOnMainResult.Failed)
        assertTrue((result as PutOnMainResult.Failed).why.endsWith("Ask the agent to commit its work first."))
        assertTrue(rig.gate.fetched.isEmpty())
        assertTrue(rig.gate.pushed.isEmpty())
    }

    @Test
    fun `a session without commits has nothing to put on main`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Question")

        val result = sessions.putOnMain(session.id)

        assertEquals(
            PutOnMainResult.Failed("There is nothing new to put on main: this session has no commits of its own yet."),
            result,
        )
    }

    @Test
    fun `a merged chat that goes on starts again from main under the same name`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Dark theme")
        rig.agentCommits(session, "app/Settings.kt", "val theme = \"dark\"\n")
        sessions.autosave(session.id)
        assertEquals(PutOnMainResult.Merged, sessions.putOnMain(session.id))

        sessions.continueSession(session.id)

        assertEquals(SessionStatus.OPEN, sessions.all.value.single().status)
        assertEquals(hostGit(rig.origin, "rev-parse", "main"), hostGit(rig.worktree(session), "rev-parse", "HEAD"))
        assertEquals(session.branch, hostGit(rig.worktree(session), "rev-parse", "--abbrev-ref", "HEAD"))
    }
}
