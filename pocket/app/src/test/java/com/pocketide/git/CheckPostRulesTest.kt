package com.pocketide.git

import org.eclipse.jgit.lib.ObjectId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckPostRulesTest {

    @Test
    fun `AI data is recognised by path wherever it sits`() {
        listOf(
            ".claude/projects/-root-work/4f1c.jsonl",
            ".claude/projects/-root-work/memory/MEMORY.md",
            ".claude/todos/list.json",
            ".claude/statsig/cache",
            ".claude/shell-snapshots/snapshot.sh",
            ".claude/file-history/4f1c/app.kt",
            ".claude/history.jsonl",
            ".claude/.credentials.json",
            ".claude.json",
            ".claude.json.backup",
            "backup/home/.claude/projects/p/s.jsonl",
            ".codex/auth.json",
            ".codex/sessions/2026/09/24/rollout-1.jsonl",
            ".codex/history.jsonl",
            ".codex/config.toml",
            ".gemini/oauth_creds.json",
            ".gemini/antigravity/brain/1234/task.md",
            ".gemini/GEMINI.md",
            "oauth_creds.json",
            "copy/antigravity/conversations/1234.pb",
            "brain/1234/.system_generated/logs/transcript.jsonl",
        ).forEach { path -> assertEquals(path, FindingKind.AI_DATA, PathRules.check(path)?.kind) }
    }

    @Test
    fun `instruction files, project settings and templates pass`() {
        listOf(
            "CLAUDE.md", "AGENTS.md", "GEMINI.md", "docs/CLAUDE.md", "service/AGENTS.md",
            ".claude/settings.json", ".claude/commands/review.md", ".claude/agents/tester.md",
            ".claude/skills/deploy/SKILL.md", ".gemini/settings.json", "src/projects/claude.kt",
            "antigravity/README.md", ".env.example", ".env.sample", ".env.template", ".env.local.example",
            "id_rsa.pub", "keystore.properties", "app/src/main/kotlin/App.kt",
        ).forEach { path -> assertNull(path, PathRules.check(path)) }
    }

    @Test
    fun `secret files are recognised by name`() {
        listOf(
            ".env", ".env.local", ".env.production", "server/.env", "app/release.jks", "debug.keystore",
            "cert.p12", "CERT.PFX", "Upload.JKS", "id_rsa", "home/.ssh/id_ed25519",
        ).forEach { path -> assertEquals(path, FindingKind.SECRET, PathRules.check(path)?.kind) }
    }

    @Test
    fun `credential files from a home folder never pass, wherever the copy sits`() {
        // Plan A1: the same list decides what sync uploads, what Your data shows and what is pushed.
        mapOf(
            "backup/.claude/.credentials.json" to FindingKind.AI_DATA,
            "backup/.claude.json" to FindingKind.AI_DATA,
            "backup/.claude/backups/.claude.json.backup.1" to FindingKind.AI_DATA,
            "home/.config/anthropic/key" to FindingKind.SECRET,
            "home/.codex/auth.json" to FindingKind.AI_DATA,
            "home/.gemini/jetski-standalone-oauth-token" to FindingKind.AI_DATA,
            "home/.gemini/antigravity/mcp_oauth_tokens.json" to FindingKind.AI_DATA,
            ".config/gcloud/application_default_credentials.json" to FindingKind.SECRET,
            "dotfiles/.git-credentials" to FindingKind.SECRET,
            ".config/gh/hosts.yml" to FindingKind.SECRET,
            "dotfiles/.netrc" to FindingKind.SECRET,
            "dotfiles/.ssh/config" to FindingKind.SECRET,
            ".gnupg/private-keys-v1.d/key" to FindingKind.SECRET,
            ".local/share/code-server/User/globalStorage/state.vscdb" to FindingKind.SECRET,
            ".config/tool/oauth_token.json" to FindingKind.SECRET,
        ).forEach { (path, kind) -> assertEquals(path, kind, PathRules.check(path)?.kind) }
    }

    @Test
    fun `an agent's synced data counts as AI data, a project's own instructions do not`() {
        assertEquals(FindingKind.AI_DATA, PathRules.check("copy/.claude/plans/refactor.md")?.kind)
        assertEquals(FindingKind.AI_DATA, PathRules.check(".claude/projects/-root/memory/notes.md")?.kind)
        listOf(
            ".claude/CLAUDE.md", ".claude/rules/style.md", ".claude/hooks/credential_check.py",
            ".claude/skills/rotate-token/run.sh", ".github/actions/setup-credentials/action.yml",
            ".config/nvim/init.lua", ".npmrc", "src/auth/TokenStore.kt",
        ).forEach { path -> assertNull(path, PathRules.check(path)) }
    }

    @Test
    fun `build outputs are recognised, vendored libraries are not`() {
        listOf(
            "app-release.apk", "out/app.aab", "dist/App.IPA", "classes.dex", "bin/Main.class", "obj/main.o",
            "tool.exe", "Installer.dmg", "setup.msi", "app/build/outputs/mapping/release/mapping.txt",
            "app/build/intermediates/x.json", ".gradle/8.13/fileHashes/fileHashes.bin", "ios/DerivedData/Info.plist",
        ).forEach { path -> assertTrue(path, BuildOutputs.check(path) != null) }
        listOf(
            "gradle/wrapper/gradle-wrapper.jar", "app/src/main/jniLibs/arm64-v8a/libproot.so", "lib/native.dll",
            "src/build/Main.kt", "build.gradle.kts", "docs/build/outputs.md", "apk-notes.txt",
        ).forEach { path -> assertNull(path, BuildOutputs.check(path)) }
    }

    @Test
    fun `workflow code is recognised and its approval names exact content`() {
        assertTrue(WorkflowChanges.applies(".github/workflows/build.yml"))
        assertTrue(WorkflowChanges.applies(".github/actions/setup/action.yml"))
        assertFalse(WorkflowChanges.applies(".github/dependabot.yml"))
        assertFalse(WorkflowChanges.applies("sub/.github/workflows/build.yml"))

        val key = WorkflowChanges.approvalKey(".github/workflows/build.yml", ObjectId.zeroId())
        assertTrue(WorkflowChanges.isApprovalKey(key))
        assertFalse(WorkflowChanges.isApprovalKey("0000:.github/workflows/build.yml"))
        assertFalse(WorkflowChanges.isApprovalKey(ObjectId.zeroId().name + ":src/App.kt"))
        assertFalse(WorkflowChanges.isApprovalKey("anything"))
    }

    @Test
    fun `a workflow's triggers and Secrets are read the way GitHub reads them`() {
        val workflow = """
            |name: Build
            |"on":
            |  push:
            |    branches: [main]
            |  # a comment
            |  workflow_dispatch:
            |jobs:
            |  build:
            |    runs-on: ubuntu-latest
            |    steps:
            |      - run: echo ${'$'}{{ secrets.UPLOAD_KEY }} ${'$'}{{ secrets['STORE_PASSWORD'] }}
            |""".trimMargin()
        assertEquals(listOf("push", "workflow_dispatch"), WorkflowChanges.events(workflow))
        assertEquals(setOf("STORE_PASSWORD", "UPLOAD_KEY"), WorkflowChanges.secretNames(workflow))
        assertEquals(listOf("push", "pull_request"), WorkflowChanges.events("on: [push, pull_request]\njobs: {}\n"))
        assertEquals(listOf("push"), WorkflowChanges.events("on: push # every push\n"))
        assertEquals(listOf("push", "release"), WorkflowChanges.events("on:\n  - push\n  - release\n"))
        assertNull(WorkflowChanges.triggerBlock("runs:\n  using: composite\n"))
    }

    @Test
    fun `a workflow change is described by what matters for Secrets and runs`() {
        val before = "on: workflow_dispatch\njobs:\n  b:\n    steps:\n      - run: echo ${'$'}{{ secrets.A_KEY }}\n"
        val after = "on: [push, workflow_dispatch]\njobs:\n  b:\n    env:\n      ALL: ${'$'}{{ toJSON(secrets) }}\n" +
            "    steps:\n      - run: echo ${'$'}{{ secrets.A_KEY }} ${'$'}{{ secrets.B_KEY }}\n"

        assertEquals(
            "Changes GitHub Actions code in .github/workflows/b.yml. It newly uses the Secrets B_KEY. " +
                "It hands all of the project's Secrets to its steps. It runs on: push, workflow_dispatch. " +
                "Read the change and approve it before it goes to GitHub.",
            WorkflowChanges.describe(".github/workflows/b.yml", before, after),
        )
        assertEquals(
            "Adds GitHub Actions code in .github/workflows/c.yml. It runs on: pull_request_target. " +
                "Read the change and approve it before it goes to GitHub.",
            WorkflowChanges.describe(".github/workflows/c.yml", null, "on: pull_request_target\n"),
        )
    }

    @Test
    fun `the diff shows GitHub's version against the branch's`() {
        val diff = WorkflowChanges.diff(".github/workflows/b.yml", "a\nb\n".toByteArray(), "a\nc\n".toByteArray())
        assertTrue(diff, diff.startsWith("--- a/.github/workflows/b.yml\n+++ b/.github/workflows/b.yml\n@@"))
        assertTrue(diff, diff.contains("\n-b\n+c\n"))
        assertTrue(WorkflowChanges.diff("x.yml", null, "new\n".toByteArray()).startsWith("--- /dev/null\n+++ b/x.yml\n"))
        val long = WorkflowChanges.diff("x.yml", null, "line\n".repeat(40_000).toByteArray())
        assertTrue(long.endsWith("(The rest of the change is not shown.)\n"))
    }

    @Test
    fun `each token shape is found, and only once`() {
        val cases = listOf(
            fake("gh" + "p_", 36) to "GitHub token",
            fake("gh" + "u_", 36) to "GitHub token",
            fake("github" + "_pat_", 82) to "GitHub token",
            fake("AI" + "za", 35) to "Google API key",
            fake("ya" + "29.", 60) to "Google sign-in token",
            fake("xo" + "xb-", 40) to "Slack token",
            fake("sk" + "_live_", 30) to "Stripe live key",
            fake("sk-" + "ant-api03-", 90) to "Anthropic API key",
            fake("sk-" + "proj-", 100) to "OpenAI API key",
            fake("sk" + "-", 48) to "OpenAI API key",
            fake("np" + "m_", 36) to "npm token",
            fake("py" + "pi-", 80) to "PyPI token",
            fake("AGE-SECRET" + "-KEY-1", 58, "QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L") to "age secret key",
            fake("h" + "f_", 34) to "Hugging Face token",
            fake("S" + "K", 32, "0123456789abcdef") to "Twilio API key",
            "-----BEGIN " + "OPENSSH PRIVATE KEY-----" to "private key",
            "-----BEGIN " + "RSA PRIVATE KEY-----" to "private key",
        )
        cases.forEach { (secret, what) ->
            val found = SecretPatterns.find("config.txt", "value = \"$secret\"\n")
            assertEquals("$what: $found", 1, found.size)
            assertTrue("$what: $found", found.single().contains(what))
        }
    }

    @Test
    fun `ordinary text holds no secrets`() {
        val text = """
            val task = "sk-loading-spinner"
            // See https://github.com/owner/repo and the ghp_ prefix in the docs.
            commit 4f1c0b9e8d7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c
            SKIP_TESTS=true
            password: see the vault
        """.trimIndent()
        assertEquals(emptyList<String>(), SecretPatterns.find("notes.md", text))
    }

    @Test
    fun `an AWS key ID counts only with its secret nearby, or a secret with its name`() {
        val keyId = "AK" + "IA" + "Z7XQ4PLM2NB8VC3R"
        val secret = "wJ4lrXUtnFEMI/K7MDENG" + "/bPxRfiCY9x2Lm8Qa3z"
        assertEquals(40, secret.length)
        assertTrue(SecretPatterns.find("creds", "key_id = $keyId\nsecret = $secret\n").any { "AWS" in it })
        assertTrue(SecretPatterns.find("creds", "aws_secret_access_key = $secret\n").any { "AWS" in it })
        assertFalse(SecretPatterns.find("creds", "key_id = $keyId\n").any { "AWS" in it })
        // AWS's own documentation example is not a key.
        assertFalse(SecretPatterns.find("readme", "AK" + "IAIOSFODNN7EXAMPLE $secret").any { "AWS" in it })
        // A git commit ID is not a secret key.
        assertFalse(SecretPatterns.find("log", "$keyId 4f1c0b9e8d7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c").any { "AWS" in it })
    }

    @Test
    fun `Firebase config files may carry the app's API key`() {
        val key = fake("AI" + "za", 35)
        assertTrue(SecretPatterns.find("google-services.json", "\"current_key\": \"$key\"").isEmpty())
        assertTrue(SecretPatterns.find("GoogleService-Info.plist", "<string>$key</string>").isEmpty())
        assertEquals(1, SecretPatterns.find("config.js", "apiKey: \"$key\"").size)
    }

    @Test
    fun `registry passwords count in npmrc and pypirc, environment references do not`() {
        assertTrue(SecretPatterns.find(".npmrc", "//registry.npmjs.org/:_authToken=abcdef123456\n").any { "registry" in it })
        assertTrue(SecretPatterns.find(".npmrc", "//registry.npmjs.org/:_authToken=\${NPM_TOKEN}\n").isEmpty())
        assertTrue(SecretPatterns.find(".pypirc", "[pypi]\nusername = __token__\npassword = hunter2hunter2\n").any { "registry" in it })
        assertTrue(SecretPatterns.find("notes.txt", "password = hunter2hunter2\n").isEmpty())
    }

    @Test
    fun `Claude Code and Codex transcripts are recognised by their records`() {
        assertTrue(Transcripts.found("""{"parentUuid":null,"sessionId":"4f1c","type":"user","message":{"role":"user"}}"""))
        assertTrue(Transcripts.found("""{"timestamp":"2026-09-24T10:00:00Z","type":"session_meta","payload":{}}"""))
        assertTrue(Transcripts.found("""{"type":"response_item","payload":{}}"""))
        assertTrue(Transcripts.found("{\"type\":\"summary\",\"summary\":\"Fix\"}\n{\"sessionId\":\"s\",\"type\":\"assistant\"}"))
        // A first record too long to read whole is judged by its fields.
        assertTrue(Transcripts.found("""{"sessionId":"s","type":"user","message":{"content":"""" + "x".repeat(100)))
        assertFalse(Transcripts.found("""{"id":1,"type":"user"}"""))
        assertFalse(Transcripts.found("""{"event":"click","sessionId":"s"}"""))
        assertFalse(Transcripts.found("plain text\nmore text"))
        assertTrue(Transcripts.applies("logs/chat.JSONL"))
        assertFalse(Transcripts.applies("logs/chat.json"))
    }

    @Test
    fun `known values shorter than six characters are ignored`() {
        assertFalse(KnownValues(listOf("abc12")).foundIn("abc12 is here"))
        assertTrue(KnownValues(listOf("abc123")).foundIn("key=abc123"))
    }

    @Test
    fun `a value on several lines is looked for line by line`() {
        val values = KnownValues(listOf("first-line-value\nsecond-line-value\n"))
        assertTrue(values.foundIn("x = second-line-value"))
        assertFalse(values.foundIn("nothing here"))
    }

    @Test
    fun `in a binary file only a value it did not hold before counts`() {
        val values = KnownValues(listOf("s3cret-value"))
        val with = byteArrayOf(0, 1, 2) + "s3cret-value".toByteArray() + byteArrayOf(0)
        assertTrue(values.newIn(with, previous = null))
        assertTrue(values.newIn(with, previous = byteArrayOf(0, 1, 2)))
        assertFalse(values.newIn(with, previous = with))
        assertFalse(KnownValues(emptyList()).newIn(with, previous = null))
    }
}
