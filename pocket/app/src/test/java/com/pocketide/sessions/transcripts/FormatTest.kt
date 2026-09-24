package com.pocketide.sessions.transcripts

import com.pocketide.sessions.SESSION_A
import com.pocketide.sessions.TranscriptEntry
import com.pocketide.sessions.transcripts.Fixtures.CLAUDE_SESSION
import com.pocketide.sessions.transcripts.Fixtures.WORKTREE_A
import com.pocketide.sessions.transcripts.Fixtures.WORKTREE_B
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.ASSISTANT
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.TOOL
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class FormatTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun view(format: TranscriptFormat, text: String): List<TranscriptEntry> =
        TranscriptView.read(format, listOf(temp.newFile().apply { writeText(text) }))

    private fun facts(format: TranscriptFormat, text: String): FileFacts {
        val start = FileFacts("test", format.id)
        val tally = Tally(start)
        JsonLineReader(text.byteInputStream()).use { reader ->
            while (true) {
                val line = reader.next() ?: break
                format.count(line, parseObject(line), tally)
            }
        }
        return tally.facts(start, 0, "")
    }

    private fun shown(entries: List<TranscriptEntry>) = entries.map { Triple(it.role, it.text, it.imageCount) }

    private fun ms(utc: String) = Instant.parse(utc).toEpochMilli()

    @Test
    fun `claude shows what was typed and said, tool calls in one line, images counted`() {
        val entries = view(ClaudeFormat, Fixtures.claude)
        assertEquals(
            listOf(
                Triple(USER, "Fix the login bug on the settings screen", 0),
                Triple(ASSISTANT, "I'll check the login code.", 0),
                Triple(TOOL, "Read: $WORKTREE_A/app/Login.kt", 0),
                Triple(TOOL, "Bash: ./gradlew test", 0),
                Triple(USER, "Here is a screenshot of the bug", 1),
                Triple(USER, "/model sonnet", 0),
                Triple(ASSISTANT, "Fixed: the token was not refreshed.", 0),
            ),
            shown(entries),
        )
        assertEquals(ms("2026-09-24T05:00:00Z"), entries.first().at)
        assertEquals(ms("2026-09-24T05:03:00Z"), entries.last().at)
    }

    @Test
    fun `claude usage counts each response once, cache included`() {
        val facts = facts(ClaudeFormat, Fixtures.claude)
        // msg_01A (last line of it: 3010 in, 60 out) + msg_01B + the subagent's + msg_01C.
        assertEquals(3010L + 3005 + 1 + 7, facts.tokensIn)
        assertEquals(60L + 20 + 1 + 12, facts.tokensOut)
        assertEquals("Fix the login bug on the settings screen", facts.firstUserText)
        assertEquals(CLAUDE_SESSION, facts.ref)
        assertEquals(ms("2026-09-24T05:03:00Z"), facts.newestAt)
    }

    @Test
    fun `claude folder names match claude code's own algorithm`() {
        // Expected values computed by running the functions shipped in the Claude Code extension.
        assertEquals("-work-alice--demo-$SESSION_A", ClaudeFormat.projectKey("/work/alice__demo/$SESSION_A"))
        val long = "/work/someone-with-a-long-name__" + "a".repeat(90) +
            "-repository/$SESSION_A/packages/frontend/src/components/very/deep/folder"
        assertEquals(
            "-work-someone-with-a-long-name--" + "a".repeat(90) +
                "-repository-$SESSION_A-packages-frontend-src-compone-o5d4tp",
            ClaudeFormat.projectKey(long),
        )
        val accented = "/work/o__r/$SESSION_A/café/" + "x".repeat(200)
        assertEquals(
            "-work-o--r-$SESSION_A-caf--" + "x".repeat(147) + "-ix0qua",
            ClaudeFormat.projectKey(accented),
        )
    }

    @Test
    fun `claude typed text drops command output and reminders`() {
        assertEquals("/clear", ClaudeFormat.typed("<command-name>/clear</command-name>\n<command-message>clear</command-message>\n<command-args></command-args>"))
        assertNull(ClaudeFormat.typed("<local-command-stdout></local-command-stdout>"))
        assertEquals("Go on", ClaudeFormat.typed("Go on<system-reminder>The date changed.</system-reminder>"))
        assertNull(ClaudeFormat.typed("<system-reminder>only a reminder</system-reminder>"))
    }

    @Test
    fun `codex shows the owner's words, not the context codex adds`() {
        val entries = view(CodexFormat, Fixtures.codex)
        assertEquals(
            listOf(
                Triple(USER, "Add a dark theme toggle", 1),
                Triple(TOOL, "shell: rg -n theme app/src", 0),
                Triple(TOOL, "apply_patch: app/src/Theme.kt, app/src/Toggle.kt", 0),
                Triple(ASSISTANT, "Added the toggle in Settings.", 0),
                Triple(TOOL, "web search: material 3 dark theme", 0),
                Triple(TOOL, "shell: ./gradlew assembleDebug", 0),
            ),
            shown(entries),
        )
        assertEquals(ms("2026-09-24T06:00:02Z"), entries.first().at)
    }

    @Test
    fun `codex facts carry the start folder and totals that survive a restart`() {
        val facts = facts(CodexFormat, Fixtures.codex)
        assertEquals(WORKTREE_B, facts.cwd)
        assertEquals("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", facts.ref)
        assertFalse(facts.side)
        assertEquals(2500L + 300, facts.tokensIn)
        assertEquals(150L + 20, facts.tokensOut)
        assertEquals("Add a dark theme toggle", facts.firstUserText)
        assertEquals(ms("2026-09-24T07:00:03Z"), facts.newestAt)
    }

    @Test
    fun `codex per-response records win over running totals`() {
        val facts = facts(CodexFormat, Fixtures.codexWithRecords)
        assertEquals(300L, facts.tokensIn)
        assertEquals(30L, facts.tokensOut)
    }

    @Test
    fun `codex recognises the context it wraps in tags`() {
        assertTrue(CodexFormat.isContext("<environment_context>\n<cwd>/work</cwd>\n</environment_context>"))
        assertTrue(CodexFormat.isContext("  <user_shell_command attr=\"1\">ls</user_shell_command> "))
        assertTrue(CodexFormat.isContext("# AGENTS.md instructions for /work/x\n\n<INSTRUCTIONS>\nx\n</INSTRUCTIONS>"))
        assertFalse(CodexFormat.isContext("<div>Make this a card</div> please"))
        assertFalse(CodexFormat.isContext("Fix <b>bold</b> text"))
    }

    @Test
    fun `codex marks subagent threads`() {
        val meta = """{"timestamp":"2026-09-24T06:00:00Z","type":"session_meta","payload":{"id":"t2","parent_thread_id":"t1","cwd":"$WORKTREE_B","timestamp":"x","originator":"o","cli_version":"1"}}""" + "\n"
        assertTrue(facts(CodexFormat, meta).side)
    }

    @Test
    fun `antigravity steps show the prompt, the answers and their tool calls`() {
        val entries = view(AntigravityFormat, Fixtures.antigravity(WORKTREE_A))
        assertEquals(
            listOf(
                Triple(USER, "Make the header sticky", 1),
                Triple(ASSISTANT, "I'll update the header styles.", 0),
                Triple(TOOL, "view_file: $WORKTREE_A/src/Header.css", 0),
                Triple(TOOL, "run_command: npm test", 0),
                Triple(ASSISTANT, "Done. The header now stays on top.", 0),
            ),
            shown(entries),
        )
        assertEquals(ms("2026-09-24T08:00:00Z"), entries.first().at)
    }

    @Test
    fun `antigravity facts count the worktree paths a conversation names`() {
        val facts = facts(AntigravityFormat, Fixtures.antigravity(WORKTREE_A))
        assertEquals(mapOf(WORKTREE_A to 3), facts.mentions)
        assertEquals("Make the header sticky", facts.firstUserText)
        assertEquals(ms("2026-09-24T08:01:00Z"), facts.newestAt)
    }

    @Test
    fun `tool summaries stay on one short line`() {
        val summary = ToolSummary.of("Bash", parseObject("""{"command":"echo one\necho two ${"x".repeat(300)}"}"""))
        assertFalse(summary.contains('\n'))
        assertTrue(summary.length <= 161)
        assertTrue(summary.startsWith("Bash: echo one echo two"))
        assertEquals("TodoWrite", ToolSummary.of("TodoWrite", parseObject("""{"todos":[{"content":"a"}]}""")))
        assertEquals("Tool", ToolSummary.of(null, null))
    }
}
