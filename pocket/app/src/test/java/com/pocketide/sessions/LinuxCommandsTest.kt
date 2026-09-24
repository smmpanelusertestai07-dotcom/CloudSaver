package com.pocketide.sessions

import com.pocketide.core.Clock
import com.pocketide.linux.ComputerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The exact commands sessions run inside Linux, and what never goes into them. */
class LinuxCommandsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun manager(computer: ScriptedComputer, branchTaken: Boolean = false): SessionManager {
        val dirs = testDirs(temp.root)
        val refs = mutableListOf("refs/heads/main", "refs/remotes/origin/main")
        if (branchTaken) refs += "refs/remotes/origin/pocket/claude/2026-09-24-login-fix"
        fakeBareClone(dirs, *refs.toTypedArray())
        val env = TestSessionEnv(FakeProjects(listOf(project())), computer, FakeGitGate())
        return SessionManager(env, dirs, Clock { 1_790_000_000_000 }, scope, Dispatchers.IO, branchDate = { "2026-09-24" })
    }

    @Test
    fun `a session's worktree is added and locked by git inside linux, in the agent's room`() = runBlocking<Unit> {
        val computer = ScriptedComputer { 0 to emptyList() }
        val session = manager(computer).start(PROJECT_ID, "claude", "Login fix")

        val add = computer.commands.single()
        assertEquals(
            listOf(
                "-C", "/repos/alice__demo.git", "worktree", "add", "--lock", "--no-track",
                "-b", "pocket/claude/2026-09-24-login-fix", "/work/alice__demo/${session.id}", "refs/remotes/origin/main",
            ),
            gitArgs(add),
        )
        assertEquals(setOf("/repos", "/work"), add.binds.map { it.guestPath }.toSet())
        assertTrue(add.binds.single { it.guestPath == "/work" }.hostPath.endsWith("/work/claude"))
        assertEquals("/dev/null", add.env["GIT_CONFIG_GLOBAL"])
        assertTrue("hooks never run", add.argv.containsAll(listOf("-c", "core.hooksPath=/dev/null")))
        val everything = add.argv + add.env.values
        assertFalse("no token goes into Linux", everything.any { it.contains("test-token") })
    }

    @Test
    fun `a name taken on github is skipped`() = runBlocking<Unit> {
        val computer = ScriptedComputer { 0 to emptyList() }
        val session = manager(computer, branchTaken = true).start(PROJECT_ID, "claude", "Login fix")
        assertEquals("pocket/claude/2026-09-24-login-fix-2", session.branch)
    }

    @Test
    fun `a name git refuses as taken is retried with the next number`() = runBlocking<Unit> {
        var first = true
        val computer = ScriptedComputer {
            if (first) {
                first = false
                255 to listOf("fatal: a branch named 'pocket/claude/2026-09-24-login-fix' already exists")
            } else {
                0 to emptyList()
            }
        }
        val session = manager(computer).start(PROJECT_ID, "claude", "Login fix")
        assertEquals("pocket/claude/2026-09-24-login-fix-2", session.branch)
        assertEquals(2, computer.commands.size)
    }

    @Test
    fun `git's own failure reaches the owner in one plain sentence`() = runBlocking<Unit> {
        val computer = ScriptedComputer { 128 to listOf("fatal: invalid reference: refs/remotes/origin/main") }
        try {
            manager(computer).start(PROJECT_ID, "claude", "x")
            fail("The worktree could not be made")
        } catch (expected: SessionException) {
            assertEquals("Could not create the session's folder: Invalid reference: refs/remotes/origin/main.", expected.message)
        }
    }

    @Test
    fun `without a computer nothing runs, and the owner is told what to do`() = runBlocking<Unit> {
        val computer = ScriptedComputer { 0 to emptyList() }
        computer.state.value = ComputerState.NotInstalled
        try {
            manager(computer).start(PROJECT_ID, "claude", "x")
            fail("No computer")
        } catch (expected: SessionException) {
            assertEquals("Set up the computer first.", expected.message)
        }
        assertTrue(computer.commands.isEmpty())
    }
}
