package com.pocketide.git

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
