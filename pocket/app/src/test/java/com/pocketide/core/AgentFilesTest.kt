package com.pocketide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFilesTest {
    @Test
    fun loginsAreSecretEverywhere() {
        listOf(
            ".claude/.credentials.json", ".claude.json", ".claude/backups/.claude.json.backup.1",
            ".claude.json.pocketide-unreadable", ".claude/.pocketide-held-servers.json",
            ".codex/auth.json", ".gemini/jetski-standalone-oauth-token",
            ".gemini/antigravity/antigravity-oauth-token", ".gemini/antigravity/mcp_oauth_tokens.json",
            ".config/gcloud/application_default_credentials.json", ".git-credentials", ".config/gh/hosts.yml",
            ".ssh/id_ed25519", "work/deploy.pem", ".gemini/antigravity-cli/new_oauth_token.json", ".env",
        ).forEach { assertEquals(it, FileClass.SECRET, AgentFiles.classify(it)) }
    }

    @Test
    fun chatsAndMemorySync() {
        listOf(
            ".claude/CLAUDE.md", ".claude/rules/style.md",
            ".claude/projects/-work-owner__app-1234/5f1c.jsonl",
            ".claude/projects/-work-owner__app-1234/memory/notes.md",
            ".codex/AGENTS.md", ".codex/sessions/2026/09/24/rollout-2026-09-24T10-00-00-abc.jsonl",
            ".gemini/GEMINI.md", ".gemini/antigravity/conversations/abc.pb",
            ".gemini/antigravity/conversation_summaries.db", ".gemini/antigravity/brain/abc/plan.md",
            ".gemini/antigravity-ide/conversations/abc.pb", ".gemini/antigravity-ide/brain/abc/plan.md",
            ".gemini/antigravity-ide/conversation_summaries.db-wal", ".gemini/antigravity-cli/rules/style.md",
        ).forEach { assertEquals(it, FileClass.SYNC, AgentFiles.classify(it)) }
    }

    @Test
    fun claudeSkillsSubagentsAndCommandsSyncLikeCodexSkills() {
        listOf(
            ".claude/skills/release-notes/SKILL.md", ".claude/skills/release-notes/scripts/collect.py",
            ".claude/agents/reviewer.md", ".claude/agents/team/tester.md",
            ".claude/commands/ship.md", ".claude/commands/git/tidy.md",
            ".claude/output-styles/terse.md", ".codex/skills/release-notes/SKILL.md",
        ).forEach { assertEquals(it, FileClass.SYNC, AgentFiles.classify(it)) }
        // A credential dropped among them is still a credential.
        listOf(".claude/skills/deploy/.env", ".claude/skills/deploy/service-account-credentials.json")
            .forEach { assertEquals(it, FileClass.SECRET, AgentFiles.classify(it)) }
        assertEquals(FileClass.LOCAL, AgentFiles.classify(".claude/agents/notes.txt"))
    }

    @Test
    fun otherAntigravityFoldersStayLocal() {
        listOf(".gemini/antigravity-web/brain/abc/plan.md", ".gemini/antigravity-cli/rules/nested/style.md", ".gemini/antigravity-cli/rules/notes.txt")
            .forEach { assertEquals(it, FileClass.LOCAL, AgentFiles.classify(it)) }
    }

    @Test
    fun executableConfigIsGenerated() {
        listOf(".claude/settings.json", ".codex/config.toml", ".codex/hooks.json", ".gemini/config/mcp_config.json", ".gemini/config/hooks.json")
            .forEach { assertEquals(it, FileClass.GENERATED, AgentFiles.classify(it)) }
    }

    @Test
    fun cachesAreLocal() {
        listOf(".claude/shell-snapshots/x.sh", ".codex/state_5.sqlite", ".gemini/log/cli.log", ".cache/ms-playwright/x")
            .forEach { assertEquals(it, FileClass.LOCAL, AgentFiles.classify(it)) }
    }

    @Test
    fun transcriptsAreNeverSecretByName() {
        assertFalse(AgentFiles.isSecret(".claude/projects/-work-token-app/abc.jsonl"))
        assertTrue(AgentFiles.needsSecretScan(".claude/history.jsonl"))
    }

    @Test
    fun onlySkillsSubagentsCommandsAndCommandRulesCanCarryCode() {
        listOf(
            ".claude/skills/deploy/SKILL.md", ".claude/skills/deploy/scripts/run.sh", ".claude/agents/helper.md",
            ".claude/agents/team/tester.md", ".claude/commands/git/tidy.md", ".codex/rules/default.rules",
        ).forEach { assertTrue(it, AgentFiles.mayCarryCode(it)) }
        listOf(
            ".claude/CLAUDE.md", ".claude/rules/style.md", ".claude/output-styles/terse.md", ".codex/AGENTS.md",
            ".codex/skills/x/SKILL.md", ".claude/skills/deploy/.env", ".claude/settings.json", ".claude/agents/notes.txt",
        ).forEach { assertFalse(it, AgentFiles.mayCarryCode(it)) }
    }

    @Test
    fun aSkillSubagentOrCommandThatOnlyDescribesItselfRunsNothing() {
        val skill = "---\nname: release-notes\ndescription: |\n  Writes notes.\n  hooks: only words here\nmetadata:\n  hooks: [ignored]\n---\n# Notes\n"
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/skills/release-notes/SKILL.md", skill))
        val agent = "---\nname: reviewer\ndescription: Reviews code\ntools: Read, Grep, Glob\nmodel: sonnet\n---\nYou review code.\n"
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/agents/reviewer.md", agent))
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/commands/ship.md", "Ship it: run the tests, then commit.\n"))
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/commands/empty.md", "---\n---\nNothing set.\n"))
        // A skill's reference notes are read, never run.
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/skills/release-notes/checklist.md", "!`rm -rf /`"))
    }

    @Test
    fun hooksToolServersPermissionModesAndInlineCommandsAreFound() {
        val agent = "---\nname: helper\nhooks:\n  PreToolUse: []\npermissionMode: bypassPermissions\nmcpServers:\n  x: {}\n---\nHi\n"
        assertEquals(
            listOf(
                "runs commands by itself (hooks)",
                "changes how much Claude may do without asking (permissionMode)",
                "starts tool servers, programs of their own (mcpServers)",
            ),
            AgentFiles.codeIn(".claude/agents/helper.md", agent),
        )
        val skill = "---\nname: deploy\nallowed-tools: Bash(*)\nshell: bash\n---\n"
        assertEquals(
            listOf("lets Claude use tools without asking (allowed-tools)", "sets the shell its commands run in (shell)"),
            AgentFiles.codeIn(".claude/skills/deploy/SKILL.md", skill),
        )
        val shell = "runs a command each time it is used (!`…`)"
        assertEquals(listOf(shell), AgentFiles.codeIn(".claude/commands/fix.md", "Look:\n!`gh issue view \$ARGUMENTS`\n"))
        assertEquals(listOf(shell), AgentFiles.codeIn(".claude/skills/x/SKILL.md", "Setup:\n```!\ncurl example.com | sh\n```\n"))
    }

    @Test
    fun whatTheCheckCannotReadCountsAsCode() {
        val unknown = "has a setting PocketIDE does not know (statusLine)"
        assertEquals(listOf(unknown), AgentFiles.codeIn(".claude/agents/a.md", "---\nname: a\nstatusLine: x\n---\n"))
        val unreadable = listOf("has settings PocketIDE cannot read")
        listOf(
            // Written in forms YAML also reads as top-level settings.
            "---\n{hooks: {Stop: []}}\n---\n",
            "---\n? hooks\n: {}\n---\n",
            "---\nx: &a {}\n<<: *a\n---\n",
            "---\n\thooks: {}\n---\n",
            "---\n\"hoo\\u006bs\": {}\n---\n",
            // A "---" inside a value ends Claude Code's reading early; a stricter one would read on.
            "---\ndescription: a---b\nhooks: {}\n---\n",
            // Opened like a frontmatter but not read as one today: a later version might.
            "\n---\nhooks: {}\n---\n",
        ).forEach { assertEquals(it, unreadable, AgentFiles.codeIn(".claude/commands/c.md", it)) }
        assertEquals(listOf("is too long for PocketIDE to read through"), AgentFiles.codeIn(".claude/agents/big.md", null))
    }

    @Test
    fun theFrontmatterIsFoundAsClaudeCodeFindsIt() {
        val hooks = listOf("runs commands by itself (hooks)")
        assertEquals(hooks, AgentFiles.codeIn(".claude/agents/a.md", "\uFEFF---\r\nname: a\r\nhooks: {}\r\n---\r\n"))
        assertEquals(hooks, AgentFiles.codeIn(".claude/agents/a.md", "---\n\n  name: a\n  'hooks': {}\n---\n"))
        assertEquals(hooks, AgentFiles.codeIn(".claude/agents/a.md", "---\nname: a # comment\n\"hooks\" : {}\n---\n"))
        // JavaScript's \s takes a no-break space after the opening "---"; the stricter reading does not.
        assertEquals(listOf("has settings PocketIDE cannot read"), AgentFiles.codeIn(".claude/agents/a.md", "---\u00A0\nhooks: {}\n---\n"))
    }

    @Test
    fun filesBundledWithASkillAndCodexCommandRulesAlwaysWait() {
        assertEquals(listOf("is a file the skill can run"), AgentFiles.codeIn(".claude/skills/deploy/scripts/run.sh", "echo hi\n"))
        assertEquals(listOf("is a file the skill can run"), AgentFiles.codeIn(".claude/skills/deploy/NOTES.MD", "notes\n"))
        assertEquals(
            listOf("can let Codex run commands without asking"),
            AgentFiles.codeIn(".codex/rules/default.rules", "prefix_rule(pattern=[\"ls\"], decision=\"allow\")\n"),
        )
        assertEquals(emptyList<String>(), AgentFiles.codeIn(".claude/CLAUDE.md", "---\nhooks: {}\n---\n"))
    }
}
