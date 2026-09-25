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
}
