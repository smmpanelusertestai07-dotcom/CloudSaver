package com.pocketide.sync

import com.pocketide.git.SecretPatterns
import com.pocketide.git.fake
import com.pocketide.model.ObjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** "Break it" checks: what must never leave a room, and what leaves only masked. */
class SafetyTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)

    private val planted = mapOf(
        "claude" to listOf(
            ".claude/.credentials.json", ".claude.json", ".claude/backups/.claude.json.backup.1",
            ".config/anthropic/active_config", ".claude/settings.json", ".claude/projects/p/memory/oauth_token.json",
            ".claude/rules/deploy.pem", ".git-credentials", ".config/gh/hosts.yml",
        ),
        "codex" to listOf(".codex/auth.json", ".codex/config.toml", ".codex/hooks.json", ".codex/state_5.sqlite", ".codex/skills/x/auth.json"),
        "antigravity" to listOf(
            ".gemini/jetski-standalone-oauth-token", ".gemini/antigravity/antigravity-oauth-token",
            ".gemini/antigravity/mcp_oauth_tokens.json", ".gemini/config/mcp_config.json",
            ".gemini/antigravity-cli/settings.json", ".config/gcloud/application_default_credentials.json",
            ".gemini/antigravity/brain/abc/credentials.txt",
        ),
    )

    @Test
    fun loginsTokensAndGeneratedConfigNeverReachTheQueueOrDrive() = runBlocking {
        val phone = TestPhone(accounts, clock)
        for ((agent, paths) in planted) for (p in paths) phone.homeFile(agent, p).writeText("SECRET-$agent-$p")
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("Instructions are fine.")
        phone.mediaFile("claude", "owner/app", "s1", "id_rsa").writeText("SECRET-KEY")
        phone.mediaFile("claude", "owner/app", "s1", "app-release.apk").writeText("APK")
        phone.drive.offline = true
        phone.engine.syncNow()
        assertEquals(listOf(".claude/CLAUDE.md"), phone.queued().map { it.path })

        phone.drive.offline = false
        phone.engine.syncNow()
        val paths = phone.remoteIndex()!!.objects.map { it.path }
        assertEquals(listOf(".claude/CLAUDE.md"), paths)
        for (stored in phone.drive.files.values) {
            val plain = Codec.gunzip(phone.cipher.decryptBytes(stored.bytes)).toString(Charsets.UTF_8)
            assertFalse(plain.contains("SECRET"))
        }
    }

    @Test
    fun aLinkPlantedInARoomIsNeverFollowed() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val outside = phone.base.resolve("outside").apply { mkdirs() }
        outside.resolve("stolen.jsonl").writeText("private\n")
        val projects = java.io.File(phone.dirs.roomHome("claude"), ".claude/projects").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(projects.toPath().resolve("link"), outside.toPath())
        phone.engine.syncNow()
        assertTrue(phone.remoteIndex()?.objects.orEmpty().isEmpty())
    }

    @Test
    fun aLinkPlantedWhereARestoredChatUsedToBeWrittenCannotOverwriteTheAppsFiles() = runBlocking {
        val phone = TestPhone(accounts, clock).apply { sessions += session("s", at = clock.now, ref = "c0ffee00-1111") }
        val transcript = phone.homeFile("claude", claudeTranscript("owner/app", "s", "c0ffee00-1111"))
        transcript.writeText("PAYLOAD FROM THE ROOM\n")
        phone.engine.syncNow()
        val appFile = phone.dirs.schedules.apply { writeText("ORIGINAL APP FILE") }

        // The room removes its transcript and plants a link at the name the app wrote through before.
        transcript.delete()
        val planted = File(transcript.parentFile, ".${transcript.name}.pocketide-part").toPath()
        Files.createSymbolicLink(planted, appFile.toPath())
        phone.engine.fetchSession("s")

        assertEquals("ORIGINAL APP FILE", appFile.readText())
        assertEquals("PAYLOAD FROM THE ROOM\n", transcript.readText())
        assertTrue("the planted link is left as it was", Files.isSymbolicLink(planted))
    }

    @Test
    fun aFolderSwappedForALinkWhileAChatDownloadsReceivesNothing() = runBlocking {
        val phone = TestPhone(accounts, clock).apply { sessions += session("s", at = clock.now, ref = "c0ffee00-1111") }
        val transcript = phone.homeFile("claude", claudeTranscript("owner/app", "s", "c0ffee00-1111"))
        transcript.writeText("the chat\n")
        phone.engine.syncNow()
        transcript.delete()
        val outside = phone.base.resolve("outside").apply { mkdirs() }

        // While the app downloads, a program in the room swaps the chat's folder for a link.
        val folder = checkNotNull(transcript.parentFile).toPath()
        phone.drive.beforeDownload = { name ->
            if (name.startsWith("o-")) {
                Files.move(folder, folder.resolveSibling("moved-away"))
                Files.createSymbolicLink(folder, outside.toPath())
            }
        }
        phone.engine.fetchSession("s")

        assertTrue("nothing reaches the folder the link points to", outside.list().isNullOrEmpty())
        assertTrue(Files.isSymbolicLink(folder))
    }

    @Test
    fun aFolderPlantedWhereAChatBelongsIsLeftAloneAndStopsNothing() = runBlocking {
        val phone = TestPhone(accounts, clock).apply { sessions += session("s", at = clock.now, ref = "c0ffee00-1111") }
        val transcript = phone.homeFile("claude", claudeTranscript("owner/app", "s", "c0ffee00-1111"))
        transcript.writeText("the chat\n")
        phone.engine.syncNow()

        // The room replaces its transcript with a folder of the same name.
        transcript.delete()
        File(transcript, "inside").apply { parentFile?.mkdirs() }.writeText("the room's")
        phone.engine.fetchSession("s")

        assertEquals("the room's", File(transcript, "inside").readText())
        phone.engine.syncNow()
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
    }

    @Test
    fun pastedKeysInPromptHistoryAreMaskedBeforeUploadAndTheFileKeepsItsLength() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val history = phone.homeFile("claude", ".claude/history.jsonl")
        val key = "sk-ant-api03-" + "A1b2C3d4".repeat(6)
        history.writeText("{\"display\":\"use $key please\"}\n")
        phone.engine.syncNow()
        history.appendText("{\"display\":\"password=hunter2hunter2\"}\n")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()

        val pieces = phone.remoteIndex()!!.objects.filter { it.path == ".claude/history.jsonl" }.sortedBy { it.offset }
        assertEquals(2, pieces.size)
        val sent = pieces.joinToString("") { p ->
            val stored = phone.drive.files.values.single { it.name == p.name }
            Codec.gunzip(phone.cipher.decryptBytes(stored.bytes)).toString(Charsets.ISO_8859_1)
        }
        assertEquals(history.length(), sent.length.toLong())
        assertFalse(sent.contains(key))
        assertFalse(sent.contains("hunter2hunter2"))
        assertTrue(sent.contains("password=**************"))
        assertTrue("the phone's own copy is untouched", history.readText().contains(key))
        assertEquals(ObjectKind.CHAT_PIECE, pieces.first().kind)

        // A new phone gets the masked history, and it rebuilds without a checksum error.
        val other = TestPhone(accounts, clock, deviceId = "b", deviceName = "B")
        other.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(sent, other.homeFile("claude", ".claude/history.jsonl").readText(Charsets.ISO_8859_1))
    }

    @Test
    fun aHistoryLineStillBeingWrittenWaitsSoASecretIsNeverSplit() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val history = phone.homeFile("codex", ".codex/history.jsonl")
        val key = "ghp_" + "Z9y8X7w6".repeat(5)
        val first = "{\"text\":\"hello\"}\n"
        history.writeText(first + "{\"text\":\"" + key.take(12))
        phone.engine.syncNow()
        val early = phone.remoteIndex()!!.objects.filter { it.path == ".codex/history.jsonl" }
        assertEquals(listOf(first.length.toLong()), early.map { it.length })

        history.appendText(key.drop(12) + "\"}\n")
        clock.advance(Durations.MINUTE)
        phone.engine.syncNow()
        val pieces = phone.remoteIndex()!!.objects.filter { it.path == ".codex/history.jsonl" }.sortedBy { it.offset }
        assertEquals(history.length(), pieces.sumOf { it.length })
        val sent = pieces.joinToString("") { p ->
            Codec.gunzip(phone.cipher.decryptBytes(phone.drive.files.values.single { it.name == p.name }.bytes)).toString(Charsets.UTF_8)
        }
        assertFalse(sent.contains(key.take(12)))
        assertFalse(sent.contains(key.drop(12)))
    }

    @Test
    fun theMaskKeepsLengthsAndLeavesOrdinaryTextAlone() {
        val text = "ghp_" + "x".repeat(36) + " and \"api_key\": \"abcdef123456\" and tokens: 12\n"
        val masked = SecretMask.mask(text.toByteArray()).toString(Charsets.UTF_8)
        assertEquals(text.length, masked.length)
        assertFalse(masked.contains("ghp_"))
        assertFalse(masked.contains("abcdef123456"))
        assertTrue(masked.contains("tokens: 12"))
        val plain = "Just a normal prompt about tokens and passwords.\n".toByteArray()
        assertTrue(SecretMask.mask(plain).contentEquals(plain))
        val streamed = SecretMaskingInputStream(ByteArrayInputStream(text.toByteArray()), maxChunk = 7).readBytes()
        assertEquals(text.length, streamed.size)
    }

    @Test
    fun everyTokenShapeTheCheckPostKnowsIsMaskedInPromptHistory() {
        val bech32 = "QPZRY9X8GF2TVDW0S3JN54KHCE6MUA7L"
        val samples = listOf(
            fake("gh" + "p_", 36),
            fake("github" + "_pat_", 82),
            fake("AI" + "za", 35),
            fake("ya" + "29.", 60),
            fake("1/" + "/0", 40),
            fake("xo" + "xb-", 40),
            fake("sk" + "_live_", 30),
            fake("rk" + "_live_", 30),
            fake("sk-" + "ant-api03-", 90),
            fake("sk-" + "proj-", 100),
            fake("sk" + "-", 48),
            fake("np" + "m_", 36),
            fake("py" + "pi-", 80),
            fake("AGE-SECRET" + "-KEY-1", 58, bech32),
            fake("h" + "f_", 34),
            fake("S" + "K", 32, "0123456789abcdef"),
            fake("AK" + "IA", 16, "ABCDEFGHJKLMNPQRSTUVWXYZ234567"),
            "aws_secret_access_key=" + fake("", 40),
            fake("gl" + "pat-", 20),
            fake("ey" + "J", 20) + "." + fake("", 20) + "." + fake("", 20),
            // A private key pasted into a prompt: one JSON line, its line breaks escaped. Split like the
            // prefixes above, so the source never holds the marker the no-secrets gate looks for.
            "-----BEGIN OPENSSH " + "PRIVATE KEY-----\\n" + fake("b3BlbnNzaC1rZXktdjEAAAAA", 40) + "\\n-----END OPENSSH PRIVATE KEY-----",
        )
        for (shape in SecretPatterns.tokenShapes) {
            assertTrue("no sample for ${shape.pattern}", samples.any { shape.containsMatchIn(it) })
        }
        for (sample in samples) {
            val line = "{\"display\":\"use $sample here\",\"timestamp\":1}\n".toByteArray()
            val masked = SecretMask.mask(line)
            val text = masked.toString(Charsets.UTF_8)
            val middle = sample.substring(sample.length / 2).take(8)
            assertEquals(sample, line.size, masked.size)
            assertFalse(sample, text.contains(middle))
            assertTrue(sample, text.startsWith("{\"display\":\"use ") && text.endsWith(" here\",\"timestamp\":1}\n"))
            assertTrue(sample, SecretMaskingInputStream(ByteArrayInputStream(line)).readBytes().contentEquals(masked))
        }
    }

    @Test
    fun anAgentDatabaseIsCopiedOnlyWhileItsRoomIsStopped() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val db = phone.homeFile("antigravity", ".gemini/antigravity/conversation_summaries.db")
        db.writeBytes(ByteArray(64) { 7 })
        db.setLastModified(clock.now - Durations.HOUR)
        phone.homeFile("antigravity", ".gemini/antigravity/conversation_summaries.db-shm").writeBytes(ByteArray(8))
        phone.runningRooms += "antigravity"
        phone.engine.syncNow()
        assertTrue(phone.remoteIndex()?.objects.orEmpty().isEmpty())

        phone.runningRooms.clear()
        phone.engine.syncNow()
        val objects = phone.remoteIndex()!!.objects
        assertEquals(listOf(".gemini/antigravity/conversation_summaries.db"), objects.map { it.path })
        assertEquals(ObjectKind.AGENT_STATE, objects.single().kind)
    }

    @Test
    fun theOwnersClaudeSkillsSubagentsAndCommandsReachDriveAsMemory() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val kept = listOf(
            ".claude/skills/release-notes/SKILL.md", ".claude/skills/release-notes/scripts/collect.py",
            ".claude/agents/reviewer.md", ".claude/commands/git/tidy.md", ".claude/output-styles/terse.md",
        )
        for (p in kept) phone.homeFile("claude", p).writeText("mine: $p")
        phone.homeFile("claude", ".claude/skills/deploy/.env").writeText("SECRET")
        phone.engine.syncNow()
        val objects = phone.remoteIndex()!!.objects
        assertEquals(kept.sorted(), objects.map { it.path }.sorted())
        assertTrue(objects.all { it.kind == ObjectKind.MEMORY })
    }
}
