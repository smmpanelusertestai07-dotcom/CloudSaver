package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.sessions.transcripts.ClaudeFormat
import com.pocketide.sessions.transcripts.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SessionLifecycleTest {
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

    private fun claudeTranscript(session: SessionRecord, text: String = Fixtures.claude): File {
        val key = ClaudeFormat.projectKey(AppDirs.guestWorktree(session.projectId, session.id))
        return File(rig.dirs.roomHome("claude"), ".claude/projects/$key/${Fixtures.CLAUDE_SESSION}.jsonl")
            .apply { parentFile?.mkdirs(); writeText(text) }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("Timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `start makes a branch named after the chat and a locked worktree inside linux`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        rig.now = java.time.Instant.parse("2026-09-24T20:00:00Z").toEpochMilli() // 25 Sep in India

        val session = sessions.start(PROJECT_ID, "claude", "Fix the login bug")

        UUID.fromString(session.id)
        assertEquals("pocket/claude/2026-09-25-fix-the-login-bug", session.branch)
        assertEquals("Fix the login bug", session.title)
        assertEquals(SessionStatus.OPEN, session.status)
        assertEquals("phone-1", session.deviceId)
        assertEquals(rig.now, session.startedAt)
        assertTrue(File(rig.worktree(session), ".git").isFile)
        assertEquals("Demo", File(rig.worktree(session), "README.txt").readText().trim())
        assertTrue(rig.dirs.sessionMedia("claude", PROJECT_ID, session.id).isDirectory)
        assertEquals(session.id, sessions.activeSession("claude"))
        val listing = hostGit(rig.bare(), "worktree", "list", "--porcelain")
        assertTrue("the worktree is locked", listing.lines().any { it.startsWith("locked") })
        val add = rig.computer.commands.first { "worktree" in it.argv && "add" in it.argv }
        assertTrue(add.binds.any { it.guestPath == "/repos" && it.hostPath == rig.dirs.repos.absolutePath })
        assertTrue(add.binds.any { it.guestPath == "/work" && it.hostPath == rig.dirs.roomWork("claude").absolutePath })
        assertEquals(listOf("session started"), rig.sync.requests)
    }

    @Test
    fun `names already taken get -2 and -3, and a missing title reads as session`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val first = sessions.start(PROJECT_ID, "claude", "Dark theme")
        val second = sessions.start(PROJECT_ID, "claude", "dark THEME!")
        rig.writeUpstreamBranch("pocket/claude/2026-09-24-dark-theme-3")
        val third = sessions.start(PROJECT_ID, "claude", "Dark theme")
        val untitled = sessions.start(PROJECT_ID, "codex", null)

        assertEquals("pocket/claude/2026-09-24-dark-theme", first.branch)
        assertEquals("pocket/claude/2026-09-24-dark-theme-2", second.branch)
        assertEquals("pocket/claude/2026-09-24-dark-theme-4", third.branch)
        assertEquals("pocket/codex/2026-09-24-session", untitled.branch)
        assertEquals(SessionManager.DEFAULT_TITLE, untitled.title)
    }

    @Test
    fun `public repositories get neutral branch names`() = runBlocking<Unit> {
        val rig = GitRig(temp.newFolder("public"), isPrivate = false)
        val session = rig.manager(scope).start(PROJECT_ID, "claude", "Secret acquisition plan")
        assertEquals("pocket/claude/2026-09-24-${session.id.replace("-", "").take(8)}", session.branch)
    }

    @Test
    fun `records survive a restart, and rename and backup choices are kept`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login")
        sessions.rename(session.id, "  Login\nfix  ")
        sessions.setBackUp(session.id, false)

        val again = rig.manager(scope)
        again.refresh()
        val stored = again.all.value.single()
        assertEquals("Login fix", stored.title)
        assertFalse(stored.backUp)
        assertEquals(session.branch, stored.branch)
        assertEquals(session.id, again.activeSession("claude"))
        try {
            again.rename(session.id, "   ")
            fail("An empty name is refused")
        } catch (expected: SessionException) {
            assertEquals("Give the chat a name.", expected.message)
        }
    }

    @Test
    fun `delete moves to recently deleted, removes the phone copy and keeps the branch`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        rig.agentCommits(session, "app/Login.kt", "class Login\n")
        val transcript = claudeTranscript(session)
        rig.now += 60_000

        sessions.delete(session.id)

        val deleted = sessions.all.value.single()
        assertEquals(SessionStatus.DELETED, deleted.status)
        assertEquals(rig.now, deleted.deletedAt)
        assertFalse("the phone copy of the transcript goes", transcript.exists())
        assertTrue("unmerged code stays", rig.localHas("refs/heads/${session.branch}"))
        assertNull(sessions.activeSession("claude"))
        assertEquals(
            "This chat is in Recently deleted. Restore it to read it.",
            sessions.transcript(session.id).single().text,
        )

        sessions.restore(session.id)
        val restored = sessions.all.value.single()
        assertEquals(SessionStatus.OPEN, restored.status)
        assertNull(restored.deletedAt)
        assertEquals(listOf(session.id), rig.sync.fetched)
    }

    @Test
    fun `a chat whose bytes did not reach drive keeps its phone copy until they do`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        val transcript = claudeTranscript(session)
        val media = File(rig.dirs.sessionMedia("claude", PROJECT_ID, session.id), "screen.png").apply { writeText("png") }
        rig.sync.notInDrive += session.id
        rig.sync.uploadFails = true

        sessions.delete(session.id)
        assertEquals("the newest bytes are queued first", listOf(session.id), rig.sync.queueRequests.first())
        assertTrue(transcript.exists())
        assertTrue(media.exists())

        sessions.refresh()
        waitFor("the leftover check") { rig.sync.queueRequests.size >= 2 }
        assertTrue("still not in Drive", transcript.exists())

        rig.sync.notInDrive.clear()
        sessions.refresh()
        waitFor("the phone copy to go") { !transcript.exists() && !media.exists() }
    }

    @Test
    fun `deleting uploads what waits, and the phone copy goes once drive has it`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        val transcript = claudeTranscript(session)
        rig.sync.notInDrive += session.id

        sessions.delete(session.id)

        assertFalse(transcript.exists())
        assertEquals(listOf(listOf(session.id), listOf(session.id)), rig.sync.queueRequests)
    }

    @Test
    fun `a chat that could not be queued keeps its phone copy`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        val transcript = claudeTranscript(session)
        rig.sync.queueFails = true

        sessions.delete(session.id)
        assertTrue(transcript.exists())

        rig.sync.queueFails = false
        sessions.refresh()
        waitFor("the phone copy to go") { !transcript.exists() }
    }

    @Test
    fun `a chat kept off drive loses its phone copy at once`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        sessions.setBackUp(session.id, false)
        val transcript = claudeTranscript(session)
        rig.sync.queueFails = true

        sessions.delete(session.id)

        assertFalse(transcript.exists())
    }

    @Test
    fun `a chat deleted on another phone keeps its phone copy until drive has all of it`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        val transcript = claudeTranscript(session)
        rig.sync.notInDrive += session.id

        sessions.adopt(listOf(sessions.all.value.single().copy(status = SessionStatus.DELETED, deletedAt = rig.now)))
        waitFor("the engine to be asked") { rig.sync.queueRequests.isNotEmpty() }
        assertTrue(transcript.exists())

        rig.sync.notInDrive.clear()
        sessions.refresh()
        waitFor("the phone copy to go") { !transcript.exists() }
    }

    @Test
    fun `a deleted chat without commits leaves no branch or worktree behind`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Just a question")

        sessions.delete(session.id)

        assertFalse(rig.localHas("refs/heads/${session.branch}"))
        assertFalse(rig.worktree(session).exists())
        sessions.restore(session.id)
        sessions.continueSession(session.id)
        assertTrue("continuing brings the worktree back", File(rig.worktree(session), ".git").isFile)
        assertTrue(rig.localHas("refs/heads/${session.branch}"))
    }

    @Test
    fun `delete forever drops the chat here and hands it to the sync engine to erase`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val kept = sessions.start(PROJECT_ID, "claude", "Keep me")
        val session = sessions.start(PROJECT_ID, "claude", "Erase me")
        rig.agentCommits(session, "a.txt", "work\n")
        val transcript = claudeTranscript(session)
        rig.sync.eraseFails = true

        sessions.deleteForever(session.id)

        assertEquals(listOf(kept.id), sessions.all.value.map { it.id })
        assertFalse(transcript.exists())
        assertFalse("a clean worktree goes", rig.worktree(session).exists())
        assertTrue("the branch with work stays", rig.localHas("refs/heads/${session.branch}"))

        // Offline: still waiting, and a restore from Drive does not bring it back.
        sessions.adopt(listOf(session.copy(status = SessionStatus.DELETED, deletedAt = 1)))
        assertEquals(listOf(kept.id), sessions.all.value.map { it.id })
        rig.sync.eraseFails = false
        sessions.refresh()
        waitFor("the erase request") { rig.sync.erasedForever == listOf(session.id) }
        sessions.erased(listOf(session.id))
        try {
            sessions.continueSession(session.id)
            fail("It is gone")
        } catch (expected: SessionException) {
            assertEquals("This chat is not on this phone.", expected.message)
        }
    }

    @Test
    fun `conflict copies from sync are listed, read-only, and cannot go on main`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login fix")
        val copy = session.copy(id = UUID.randomUUID().toString(), status = SessionStatus.CONFLICT_COPY, conflictOf = session.id)

        sessions.adopt(listOf(copy))

        assertEquals(setOf(session.id, copy.id), sessions.all.value.map { it.id }.toSet())
        assertTrue(sessions.putOnMain(copy.id) is PutOnMainResult.Failed)
        try {
            sessions.continueSession(copy.id)
            fail("A conflict copy is read only")
        } catch (expected: SessionException) {
            assertTrue(expected.message!!.startsWith("This is a conflict copy."))
        }
    }

    @Test
    fun `continue opens the room on the session and makes it the room's active one`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val first = sessions.start(PROJECT_ID, "claude", "One")
        sessions.start(PROJECT_ID, "claude", "Two")

        sessions.continueSession(first.id)

        assertEquals(listOf("claude" to first.id), rig.rooms.opened)
        assertEquals(first.id, sessions.activeSession("claude"))
        val again = rig.manager(scope)
        again.refresh()
        assertEquals(first.id, again.activeSession("claude"))
    }

    @Test
    fun `a fresh process reads the chats and the open ones from the disk before answering`() = runBlocking<Unit> {
        val first = rig.manager(scope).start(PROJECT_ID, "claude", "One")
        // A scope that never runs the start-up load, like a background job that asks first.
        val cold = rig.manager(CoroutineScope(Job().apply { cancel() }))

        assertTrue(cold.all.value.isEmpty())
        assertEquals(listOf(first.id), cold.loaded().map { it.id })
        assertEquals(first.id, cold.activeSession("claude"))
    }

    @Test
    fun `after delete everything no chat is written back`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        sessions.start(PROJECT_ID, "claude", "One")

        sessions.forgetEverything()
        sessions.refresh()
        sessions.adopt(emptyList())

        assertTrue(sessions.all.value.isEmpty())
        assertNull(sessions.activeSession("claude"))
        val again = rig.manager(CoroutineScope(Job().apply { cancel() }))
        assertTrue("nothing on the disk either", again.loaded().isEmpty())
        assertNull(again.activeSession("claude"))
    }

    @Test
    fun `refresh measures transcripts, tokens, commits and the large transcript warning`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", null)
        claudeTranscript(session)
        rig.agentCommits(session, "app/Login.kt", "class Login\n")

        sessions.refresh()

        val measured = sessions.all.value.single()
        assertEquals(1, measured.commits)
        assertEquals(1, measured.filesChanged)
        assertEquals(3010L + 3005 + 1 + 7, measured.tokensIn)
        assertEquals(60L + 20 + 1 + 12, measured.tokensOut)
        assertTrue(measured.transcriptBytes > 300_000)
        assertEquals("Fix the login bug on the settings screen", measured.title)
        assertEquals(Fixtures.CLAUDE_SESSION, measured.agentSessionRef)
        assertEquals(java.time.Instant.parse("2026-09-24T05:03:00Z").toEpochMilli(), measured.lastActivityAt)
        assertFalse(sessions.largeTranscript(session.id))

        val padding = """{"type":"file-history-snapshot","note":"${"x".repeat(1000)}"}""" + "\n"
        claudeTranscript(session, Fixtures.claude + padding.repeat(11_000))
        sessions.refresh()
        assertTrue(sessions.largeTranscript(session.id))
    }

    @Test
    fun `the view reads the session's transcript, and sync gets only files it may carry`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Login")
        val transcript = claudeTranscript(session)
        val subagent = File(transcript.parentFile, "${Fixtures.CLAUDE_SESSION}/subagents/agent-1.jsonl").apply { parentFile?.mkdirs(); writeText("{}\n") }
        File(transcript.parentFile, "${Fixtures.CLAUDE_SESSION}/credentials.json").writeText("{\"token\":\"x\"}")

        val view = sessions.transcript(session.id)
        assertEquals("Fix the login bug on the settings screen", view.first().text)
        assertEquals(1, view.single { it.imageCount > 0 }.imageCount)
        assertEquals(setOf(transcript, subagent), sessions.transcriptFiles(session.id).toSet())
    }

    @Test
    fun `hand-off starts a session in another room from this session's last commit`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val source = sessions.start(PROJECT_ID, "claude", "Dark theme")
        claudeTranscript(source)
        rig.agentCommits(source, "app/Settings.kt", "val theme = \"dark\"\n")
        File(rig.worktree(source), "notes.txt").writeText("not committed")

        val handOff = sessions.handOff(source.id, "codex")

        val target = handOff.session
        assertEquals("codex", target.agentId)
        assertEquals("pocket/codex/2026-09-24-dark-theme", target.branch)
        assertEquals("val theme = \"dark\"", File(rig.worktree(target), "app/Settings.kt").readText().trim())
        assertTrue(handOff.note.contains("Goal: Fix the login bug on the settings screen"))
        assertTrue(handOff.note.contains("- app/Settings.kt (+1 -1)"))
        assertTrue(handOff.note.contains("Where claude stopped: Fixed: the token was not refreshed."))
        assertTrue(handOff.note.contains("not committed"))
        assertEquals(target.id, sessions.activeSession("codex"))
        assertEquals(SessionStatus.OPEN, sessions.all.value.single { it.id == source.id }.status)
    }

    @Test
    fun `a branch is renamed until it is on github`() = runBlocking<Unit> {
        val rig = GitRig(temp.newFolder("public"), isPrivate = false)
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Anything")

        sessions.renameBranch(session.id, "Login fix")

        val renamed = sessions.all.value.single()
        assertEquals("pocket/claude/2026-09-24-login-fix", renamed.branch)
        assertEquals(renamed.branch, hostGit(rig.worktree(session), "rev-parse", "--abbrev-ref", "HEAD"))
        rig.agentCommits(renamed, "a.txt", "a\n")
        assertNull(sessions.autosave(session.id))
        try {
            sessions.renameBranch(session.id, "Other")
            fail("A pushed branch keeps its name")
        } catch (expected: SessionException) {
            assertTrue(expected.message!!.startsWith("This branch is already on GitHub"))
        }
    }

    @Test
    fun `saving now commits unfinished work as the owner and pushes it at once`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Unfinished")
        rig.agentCommits(session, "a.txt", "a\n")
        assertNull(sessions.autosave(session.id))
        File(rig.worktree(session), "b.txt").writeText("half done\n")

        assertNull(sessions.saveNow(session.id))

        assertEquals("", hostGit(rig.worktree(session), "status", "--porcelain"))
        assertEquals("half done\n", hostGit(rig.bare(), "show", "refs/heads/${session.branch}:b.txt") + "\n")
        assertEquals("by the owner", "Alice Example", hostGit(rig.bare(), "log", "-1", "--format=%an", "refs/heads/${session.branch}"))
        assertTrue(rig.originHas(session.branch))
    }

    @Test
    fun `files are added to the project folder under a safe new name, or to media`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Files")

        val first = sessions.addFile(session.id, "../../etc/README.txt", "hello".byteInputStream(), intoProject = true)
        assertEquals("${AppDirs.guestWorktree(PROJECT_ID, session.id)}/README (2).txt", first.guestPath)
        assertEquals("hello", File(rig.worktree(session), "README (2).txt").readText())
        assertEquals("Demo", File(rig.worktree(session), "README.txt").readText().trim())

        val hidden = sessions.addFile(session.id, ".env", "A=1".byteInputStream(), intoProject = true)
        assertTrue(hidden.guestPath.endsWith("/env"))

        val attached = sessions.addFile(session.id, "shot.png", "png".byteInputStream(), intoProject = false)
        assertEquals(3L, attached.bytes)
        assertEquals("you", rig.media.added.single().source)
        try {
            sessions.addFile(session.id, "///", "x".byteInputStream(), intoProject = true)
            fail("No usable name")
        } catch (expected: SessionException) {
            assertTrue(expected.message!!.contains("no usable name"))
        }
    }

    @Test
    fun `too big a file is refused and nothing is left behind`() {
        val dir = temp.newFolder("into")
        val big = object : java.io.InputStream() {
            var left = 2_000L
            override fun read(): Int = if (left-- > 0) 1 else -1
        }
        try {
            AddedFiles.copyInto(dir, "big.bin", big, maxBytes = 1_000)
            fail("Over the limit")
        } catch (expected: SessionException) {
            assertTrue(expected.message!!.contains("100 MB"))
        }
        assertEquals(0, dir.listFiles()!!.size)
        assertNull(AddedFiles.safeName(" .. "))
        assertEquals("a_b.txt", AddedFiles.safeName("a:b.txt"))
        val long = AddedFiles.safeName("x".repeat(300) + ".kt")
        assertNotNull(long)
        assertEquals(120, long!!.length)
        assertTrue(long.endsWith(".kt"))
    }

    @Test
    fun `removing a project waits for uncommitted and unpushed work`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val session = sessions.start(PROJECT_ID, "claude", "Work")
        File(rig.worktree(session), "draft.txt").writeText("draft")
        assertTrue(sessions.unsaved(PROJECT_ID)!!.contains("not committed"))

        hostGit(rig.worktree(session), "add", "-A")
        hostGit(rig.worktree(session), "commit", "--quiet", "-m", "Draft")
        assertTrue(sessions.unsaved(PROJECT_ID)!!.contains("not on GitHub yet"))

        assertNull(sessions.autosave(session.id))
        assertNull(sessions.unsaved(PROJECT_ID))
        sessions.release(PROJECT_ID)
        assertFalse(rig.worktree(session).exists())
        assertTrue("the media folder stays with the chat", rig.dirs.sessionMedia("claude", PROJECT_ID, session.id).isDirectory)
    }

    @Test
    fun `before everything is deleted the code is saved to github and what cannot be is named`() = runBlocking<Unit> {
        val sessions = rig.manager(scope)
        val saved = sessions.start(PROJECT_ID, "claude", "Saved")
        rig.agentCommits(saved, "a.txt", "a\n")
        File(rig.worktree(saved), "b.txt").writeText("half done\n")

        assertEquals(emptyList<String>(), sessions.codeOnlyOnPhone())
        assertTrue("committed and pushed first", rig.originHas(saved.branch))
        assertEquals("", hostGit(rig.worktree(saved), "status", "--porcelain"))

        val blocked = sessions.start(PROJECT_ID, "codex", "Blocked")
        rig.agentCommits(blocked, "c.txt", "c\n")
        rig.gate.blockNextPush = com.pocketide.git.Verdict(false, emptyList(), 1)

        val left = sessions.codeOnlyOnPhone()
        assertEquals(1, left.size)
        assertTrue(left.single(), left.single().startsWith("alice/demo: The branch ${blocked.branch} has commits that are not on GitHub yet"))
        assertFalse(rig.originHas(blocked.branch))
    }
}
