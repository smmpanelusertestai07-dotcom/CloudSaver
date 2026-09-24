package com.pocketide.sessions.transcripts

import com.pocketide.core.AppDirs
import com.pocketide.model.SessionRecord
import com.pocketide.projects.JsonFile
import com.pocketide.sessions.PROJECT_ID
import com.pocketide.sessions.SESSION_A
import com.pocketide.sessions.SESSION_B
import com.pocketide.sessions.testDirs
import com.pocketide.sessions.transcripts.Fixtures.WORKTREE_A
import com.pocketide.sessions.transcripts.Fixtures.WORKTREE_B
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptIndexTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dirs: AppDirs by lazy { testDirs(temp.root) }
    private val store: File by lazy { File(dirs.vault, "transcripts.json") }

    private fun session(id: String, agent: String, ref: String? = null) = SessionRecord(
        id = id, agentId = agent, projectId = PROJECT_ID, title = "t", branch = "pocket/$agent/2026-09-24-t",
        startedAt = 0, lastActivityAt = 0, deviceId = "phone-1", agentSessionRef = ref,
    )

    private fun TestScope.index() =
        TranscriptIndex(dirs, JsonFile(store, ListSerializer(FileFacts.serializer())), StandardTestDispatcher(testScheduler))

    private fun write(file: File, text: String) = file.apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `claude folders are matched by the worktree path, subfolders included, memory left out`() = runTest {
        val projects = File(dirs.roomHome("claude"), ".claude/projects")
        val keyA = ClaudeFormat.projectKey(WORKTREE_A)
        write(File(projects, "$keyA/one.jsonl"), Fixtures.claude)
        write(File(projects, "$keyA-app/two.jsonl"), Fixtures.claude)
        write(File(projects, "$keyA/one/subagents/agent-1.jsonl"), "{}\n")
        write(File(projects, "$keyA/one.orphaned-1-x.jsonl"), "{}\n")
        write(File(projects, "$keyA/memory/MEMORY.md"), "notes")
        write(File(projects, "${ClaudeFormat.projectKey(WORKTREE_B)}/three.jsonl"), Fixtures.claude)

        val found = index().scan(listOf(session(SESSION_A, "claude"), session(SESSION_B, "claude")))

        val a = found.getValue(SESSION_A)
        assertEquals(setOf("one.jsonl", "two.jsonl"), a.conversations.map { it.file.name }.toSet())
        assertEquals(setOf("one.jsonl", "one", "one.orphaned-1-x.jsonl", "two.jsonl"), a.roots.map { it.name }.toSet())
        assertFalse(a.files().any { it.name == "MEMORY.md" })
        assertEquals(2 * (3010L + 3005 + 1 + 7), a.tokensIn)
        assertEquals(Fixtures.CLAUDE_SESSION, a.ref)
        assertEquals(listOf("three.jsonl"), found.getValue(SESSION_B).conversations.map { it.file.name })
    }

    @Test
    fun `codex threads belong to the session they started in`() = runTest {
        val sessions = File(dirs.roomHome("codex"), ".codex/sessions/2026/09/24")
        write(File(sessions, "rollout-2026-09-24T06-00-00-aaa.jsonl"), Fixtures.codex)
        val inSubfolder = Fixtures.codex.replace("\"cwd\":\"$WORKTREE_B\"", "\"cwd\":\"$WORKTREE_B/app\"")
        write(File(dirs.roomHome("codex"), ".codex/archived_sessions/rollout-2026-09-24T07-00-00-bbb.jsonl"), inSubfolder)
        val elsewhere = Fixtures.codex.replace(WORKTREE_B, "/root/scratch")
        write(File(sessions, "rollout-2026-09-24T08-00-00-ccc.jsonl"), elsewhere)

        val found = index().scan(listOf(session(SESSION_A, "codex"), session(SESSION_B, "codex")))

        assertNull(found[SESSION_A])
        val b = found.getValue(SESSION_B)
        assertEquals(setOf("rollout-2026-09-24T06-00-00-aaa.jsonl", "rollout-2026-09-24T07-00-00-bbb.jsonl"), b.conversations.map { it.file.name }.toSet())
        assertTrue(b.conversations.all { it.facts.ref.orEmpty().startsWith("/root/.codex/") })
        assertEquals(2 * 2800L, b.tokensIn)
    }

    @Test
    fun `a compressed codex thread keeps what was read before, or is found by its reference`() = runTest {
        val folder = File(dirs.roomHome("codex"), ".codex/sessions/2026/09/24")
        val plain = write(File(folder, "rollout-2026-09-24T06-00-00-aaa.jsonl"), Fixtures.codex)
        val index = index()
        val sessions = listOf(session(SESSION_B, "codex"))
        assertEquals(2800L, index.scan(sessions).getValue(SESSION_B).tokensIn)

        assertTrue(plain.renameTo(File(folder, plain.name + ".zst")))
        val carried = index.scan(sessions).getValue(SESSION_B)
        assertEquals(listOf(plain.name + ".zst"), carried.conversations.map { it.file.name })
        assertEquals(2800L, carried.tokensIn)

        val unknown = write(File(folder, "rollout-2026-09-01T06-00-00-old.jsonl.zst"), "compressed bytes")
        val ref = "/root/.codex/sessions/2026/09/24/" + unknown.name.removeSuffix(".zst")
        val byRef = index.scan(listOf(session(SESSION_A, "codex", ref = ref)))
        assertEquals(listOf(unknown.name), byRef.getValue(SESSION_A).conversations.map { it.file.name })
    }

    @Test
    fun `an antigravity conversation belongs to the session whose worktree it names most`() = runTest {
        val brain = File(dirs.roomHome("antigravity"), ".gemini/antigravity/brain")
        write(File(brain, "conv-a/.system_generated/logs/transcript.jsonl"), Fixtures.antigravity(WORKTREE_A) + "\"see $WORKTREE_B once\"\n")
        write(File(brain, "conv-a/task.md"), "- [x] header")
        write(File(brain, "conv-none/.system_generated/logs/transcript.jsonl"), """{"type":"USER_INPUT","content":"hello"}""" + "\n")
        write(File(dirs.roomHome("antigravity"), ".gemini/antigravity-cli/brain/conv-b/.system_generated/logs/transcript.jsonl"), Fixtures.antigravity(WORKTREE_B))

        val found = index().scan(listOf(session(SESSION_A, "antigravity"), session(SESSION_B, "antigravity")))

        val a = found.getValue(SESSION_A)
        assertEquals("conv-a", a.ref)
        assertEquals(listOf("conv-a"), a.roots.map { it.name })
        assertTrue(a.files().any { it.name == "task.md" })
        assertEquals("conv-b", found.getValue(SESSION_B).ref)
    }

    @Test
    fun `only appended lines are read again, a rewritten file from the start`() = runTest {
        val file = write(File(dirs.roomHome("antigravity"), ".gemini/antigravity/brain/c/.system_generated/logs/transcript.jsonl"), Fixtures.antigravity(WORKTREE_A))
        val index = index()
        val sessions = listOf(session(SESSION_A, "antigravity"))
        val first = index.scan(sessions).getValue(SESSION_A).conversations.single().facts
        assertEquals(file.length(), first.offset)
        assertEquals(3, first.mentions[WORKTREE_A])

        file.appendText("""{"type":"PLANNER_RESPONSE","created_at":"2026-09-24T09:00:00Z","content":"More in $WORKTREE_A"}""" + "\n")
        val second = index.scan(sessions).getValue(SESSION_A).conversations.single().facts
        assertEquals(4, second.mentions[WORKTREE_A])
        assertEquals(file.length(), second.offset)
        assertEquals(isoMillis("2026-09-24T09:00:00Z"), second.newestAt)

        file.writeText("""{"type":"USER_INPUT","created_at":"2026-09-25T09:00:00Z","content":"Rewritten $WORKTREE_A"}""" + "\n")
        val third = index.scan(sessions).getValue(SESSION_A).conversations.single().facts
        assertEquals(1, third.mentions[WORKTREE_A])
        assertEquals("Rewritten $WORKTREE_A", third.firstUserText)
    }

    @Test
    fun `facts survive a restart and are forgotten with their files`() = runTest {
        val file = write(File(dirs.roomHome("claude"), ".claude/projects/${ClaudeFormat.projectKey(WORKTREE_A)}/one.jsonl"), Fixtures.claude)
        val sessions = listOf(session(SESSION_A, "claude"))
        index().scan(sessions)
        assertTrue(store.readText().contains(file.absolutePath))

        val again = index().scan(sessions).getValue(SESSION_A)
        assertEquals(file.length(), again.conversations.single().facts.offset)

        index().forget(listOf(file.parentFile))
        assertFalse(store.readText().contains(file.absolutePath))
    }
}
