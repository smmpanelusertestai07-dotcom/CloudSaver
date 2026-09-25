package com.pocketide.sync

import com.pocketide.core.Settings
import com.pocketide.model.ObjectKind
import com.pocketide.model.Project
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val day = Durations.DAY

    private fun obj(name: String, kind: ObjectKind, stored: Long, session: String? = null, path: String = name) = VaultObject(
        name = name, kind = kind, sessionId = session, agentId = "claude", path = path,
        length = stored, storedBytes = stored, sha256 = name, createdAt = 1,
    )

    @Test
    fun thePlanDownloadsTheEssentialsAndTheLastThirtyDaysNow() {
        val now = clock.now
        val index = VaultIndex(
            updatedAt = now,
            sessions = listOf(
                session("recent", at = now - 3 * day),
                session("month-old", at = now - 29 * day),
                session("older", at = now - 45 * day),
                session("deleted", at = now - day, deletedAt = now - day),
            ),
            objects = listOf(
                obj("o-mem", ObjectKind.MEMORY, 100, path = ".claude/CLAUDE.md"),
                obj("o-sec", ObjectKind.SECRETS, 50, path = SECRETS_PATH),
                obj("o-hist", ObjectKind.CHAT_PIECE, 70, path = ".claude/history.jsonl"),
                obj("o-r1", ObjectKind.CHAT_PIECE, 1_000, "recent", "t/recent.jsonl"),
                obj("o-rv", ObjectKind.MEDIA, 4_000, "recent", "app/.media/recent/run.mp4"),
                obj("o-m1", ObjectKind.CHAT_PIECE, 500, "month-old", "t/month.jsonl"),
                obj("o-old", ObjectKind.CHAT_PIECE, 9_000, "older", "t/older.jsonl"),
                obj("o-del", ObjectKind.CHAT_PIECE, 300, "deleted", "t/deleted.jsonl"),
            ),
        )
        val onWifi = RestorePlanner.plan(index, indexBytes = 10, now = now, onWifi = true, freeBytes = 12_345, videosNow = true)
        assertEquals(15_030L, onWifi.driveTotalBytes)
        assertEquals(10 + 100 + 50 + 70 + 1_000 + 4_000 + 500L, onWifi.downloadNowBytes)
        assertEquals(9_000 + 300L, onWifi.laterBytes)
        assertEquals(2, onWifi.sessionsNow)
        assertEquals("deleted chats are neither now nor later", 1, onWifi.sessionsLater)
        assertTrue(onWifi.onWifi)
        assertEquals(12_345L, onWifi.freeBytes)

        val mobile = RestorePlanner.plan(index, indexBytes = 10, now = now, onWifi = false, freeBytes = 0, videosNow = false)
        assertEquals("videos wait for Wi-Fi", onWifi.downloadNowBytes - 4_000, mobile.downloadNowBytes)
        assertEquals(onWifi.laterBytes + 4_000, mobile.laterBytes)
    }

    @Test
    fun theOrderIsMemorySettingsSecretsThenChatsNewestFirst() {
        val now = clock.now
        val index = VaultIndex(
            updatedAt = now,
            sessions = listOf(session("a", at = now - 5 * day), session("b", at = now - day)),
            objects = listOf(
                obj("o-a", ObjectKind.CHAT_PIECE, 1, "a", "t/a.jsonl"),
                obj("o-sec", ObjectKind.SECRETS, 1, path = SECRETS_PATH),
                obj("o-b", ObjectKind.CHAT_PIECE, 1, "b", "t/b.jsonl"),
                obj("o-state", ObjectKind.AGENT_STATE, 1, path = ".gemini/antigravity/conversation_summaries.db"),
                obj("o-mem", ObjectKind.MEMORY, 1, path = ".codex/AGENTS.md"),
            ),
        )
        val order = RestorePlanner.select(index, now, videosNow = true).now.map { it.first().name }
        assertEquals(listOf("o-mem", "o-sec", "o-state", "o-b", "o-a"), order)
    }

    @Test
    fun aNewPhoneGetsItsDataBackAndOlderChatsWhenOpened() = runBlocking {
        val old = TestPhone(accounts, clock).apply {
            sessions += session("old-chat", at = clock.now - 60 * day, ref = "aaaa1111-old")
            sessions += session("new-chat", at = clock.now, ref = "bbbb2222-new")
            projects += Project(id = "owner/app", owner = "owner", repo = "app", addedAt = clock.now, lastActivityAt = clock.now, cloned = true)
            secrets = "API_URL=https://example.com".toByteArray()
            settings.update { it.copy(keepChatsMonths = 18) }
        }
        old.homeFile("claude", ".claude/CLAUDE.md").writeText("House rules.")
        val oldPath = claudeTranscript("owner/app", "old-chat", "aaaa1111-old")
        val newPath = claudeTranscript("owner/app", "new-chat", "bbbb2222-new")
        old.homeFile("claude", oldPath).writeText("from long ago\n")
        old.homeFile("claude", newPath).writeText("first\n")
        old.engine.syncNow()
        old.homeFile("claude", newPath).appendText("second\n")
        clock.advance(Durations.MINUTE)
        old.engine.syncNow()

        val fresh = TestPhone(accounts, clock, deviceId = "new-phone", deviceName = "New phone", settings = Settings())
        val plan = fresh.engine.restorePlan()
        assertEquals(1, plan.sessionsNow)
        assertEquals(1, plan.sessionsLater)
        fresh.engine.restore(RestoreChoice.WIFI_ONLY)

        assertEquals("House rules.", fresh.homeFile("claude", ".claude/CLAUDE.md").readText())
        assertEquals("first\nsecond\n", fresh.homeFile("claude", newPath).readText())
        assertFalse("older chats stay in Drive until opened", fresh.homeFile("claude", oldPath).exists())
        assertArrayEquals("API_URL=https://example.com".toByteArray(), fresh.imported)
        assertEquals(18, fresh.settings.settings.value.keepChatsMonths)
        assertEquals(setOf("old-chat", "new-chat"), fresh.sessions.map { it.id }.toSet())
        assertEquals(listOf("owner/app"), fresh.projects.map { it.id })
        assertTrue(fresh.engine.status.value is SyncStatus.UpToDate)

        fresh.engine.fetchSession("old-chat")
        assertEquals("from long ago\n", fresh.homeFile("claude", oldPath).readText())
    }

    @Test
    fun aFileAlreadyOnTheNewPhoneNeverReplacesDrivesCopy() = runBlocking {
        val history = ".claude/history.jsonl"
        val oldPrompts = "{\"display\":\"old prompt 1\"}\n{\"display\":\"old prompt 2\"}\n"
        val newPrompt = "{\"display\":\"new phone prompt\"}\n"
        val old = TestPhone(accounts, clock)
        old.homeFile("claude", history).writeText(oldPrompts)
        old.engine.syncNow()
        clock.advance(LeasePolicy.TTL_MS + 1)

        // The new phone's agent wrote its own history before the restore brought Drive's.
        val fresh = TestPhone(accounts, clock, deviceId = "new-phone", deviceName = "New phone")
        fresh.homeFile("claude", history).writeText(newPrompt)
        fresh.engine.restore(RestoreChoice.WIFI_ONLY)
        fresh.engine.syncNow()

        assertEquals(oldPrompts, fresh.homeFile("claude", history).readText())
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals("Drive keeps the old prompts", oldPrompts, reader.homeFile("claude", history).readText())
        val copy = fresh.remoteIndex()!!.objects.single { it.path.startsWith(Conflicts.FOLDER) && it.path.endsWith(history) }
        val stored = fresh.drive.files.getValue(copy.driveId!!).bytes
        assertEquals("the new phone's prompt is kept as a conflict copy", newPrompt, Codec.gunzip(fresh.cipher.decryptBytes(stored)).toString(Charsets.UTF_8))
    }

    @Test
    fun removingAPhonesOwnCopyNeverRemovesDrivesVersionItNeverHad() = runBlocking {
        val rules = ".claude/rules/house.md"
        val old = TestPhone(accounts, clock)
        old.homeFile("claude", rules).writeText("The old phone's rules.")
        old.engine.syncNow()

        // A second phone has its own copy while the first holds the lease, and then deletes it.
        val other = TestPhone(accounts, clock, deviceId = "other", deviceName = "Other")
        val mine = other.homeFile("claude", rules).apply { writeText("Another phone's rules.") }
        other.engine.syncNow()
        mine.delete()
        clock.advance(LeasePolicy.TTL_MS + 1)
        other.engine.syncNow()
        clock.advance(2 * Durations.HOUR)
        other.engine.syncNow()

        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals("The old phone's rules.", reader.homeFile("claude", rules).readText())
    }

    @Test
    fun aWifiOnlyRestorePausesOnMobileDataAndContinuesLater() = runBlocking {
        val old = TestPhone(accounts, clock).apply { sessions += session("s", at = clock.now, ref = "cccc3333") }
        val path = claudeTranscript("owner/app", "s", "cccc3333")
        old.homeFile("claude", path).writeText("chat\n")
        old.engine.syncNow()

        val fresh = TestPhone(accounts, clock, deviceId = "new-phone", deviceName = "New phone")
        fresh.network.metered = true
        fresh.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(SyncStatus.Error(Plain.RESTORE_WAITS), fresh.engine.status.value)
        assertFalse(fresh.homeFile("claude", path).exists())
        assertTrue(fresh.state().restore != null)

        fresh.network.metered = false
        assertEquals(WorkResult.OK, fresh.engine.runScheduled {})
        assertEquals("chat\n", fresh.homeFile("claude", path).readText())
        assertEquals(null, fresh.state().restore)
    }

    @Test
    fun aDamagedPieceIsNeverWrittenToThePhone() = runBlocking {
        val old = TestPhone(accounts, clock).apply { sessions += session("s", at = clock.now, ref = "dddd4444") }
        val path = claudeTranscript("owner/app", "s", "dddd4444")
        old.homeFile("claude", path).writeText("good data\n")
        old.engine.syncNow()
        val stored = old.drive.files.values.single { it.name.startsWith("o-") }
        stored.bytes = old.cipher.encryptBytes(Codec.gzip("evil data\n".toByteArray()))

        val fresh = TestPhone(accounts, clock, deviceId = "new-phone", deviceName = "New phone")
        val failure = runCatching { fresh.engine.fetchSession("s") }.exceptionOrNull()
        assertEquals(Materializer.BROKEN, failure?.message)
        assertFalse(fresh.homeFile("claude", path).exists())
    }
}
