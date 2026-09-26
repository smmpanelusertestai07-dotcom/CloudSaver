package com.pocketide.sync

import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionStatus
import com.pocketide.model.VaultIndex
import com.pocketide.model.VaultObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexMergeTest {
    private val clock = FakeClock()
    private val day = Durations.DAY

    private fun obj(name: String, session: String? = null) = VaultObject(
        name = name, kind = ObjectKind.MEDIA, sessionId = session, agentId = "claude", path = "p/.media/$session/$name.png",
        length = 10, storedBytes = 20, sha256 = name, createdAt = 1,
    )

    @Test
    fun twoIndexesMergeAsTheUnionPreferringNewerSessionsAndDeletions() {
        val t = clock.now
        val a = VaultIndex(
            updatedAt = t, revision = 4,
            sessions = listOf(session("s1", at = t - day), session("s2", at = t), session("s3", at = t - 2 * day, deletedAt = t - day)),
            objects = listOf(obj("o-a", "s1"), obj("o-shared", "s2")),
        )
        val b = VaultIndex(
            updatedAt = t + 1, revision = 7,
            sessions = listOf(
                session("s1", at = t).copy(title = "Renamed on B"),
                session("s2", at = t - day, deletedAt = t - 3 * day),
                session("s3", at = t),
                session("s4", at = t),
            ),
            objects = listOf(obj("o-b", "s4"), obj("o-shared", "s2")),
        )
        val merged = IndexMerge.merge(a, b)

        assertEquals(7, merged.revision)
        assertEquals(setOf("o-a", "o-b", "o-shared"), merged.objects.map { it.name }.toSet())
        assertEquals(3, merged.objects.size)
        val byId = merged.sessions.associateBy { it.id }
        assertEquals("Renamed on B", byId.getValue("s1").title)
        assertEquals("a deletion wins even over newer activity", t - 3 * day, byId.getValue("s2").deletedAt)
        assertEquals(SessionStatus.DELETED, byId.getValue("s2").status)
        assertEquals(t - day, byId.getValue("s3").deletedAt)
        assertTrue("s4" in byId)
    }

    @Test
    fun aWriteNeverErasesASessionItAlsoRestores() {
        val t = clock.now
        val base = VaultIndex(
            updatedAt = t, revision = 3,
            sessions = listOf(session("s", at = t - 31 * day, deletedAt = t - 30 * day), session("gone", at = t - 31 * day, deletedAt = t - 31 * day)),
            objects = listOf(obj("o-s", "s"), obj("o-gone", "gone")),
        )
        // Restored on its last day while the daily job's erase goes out in the same write.
        val delta = IndexDelta(sessions = listOf(SessionChange.Restore(session("s", at = t - 31 * day))), eraseSessions = setOf("s", "gone"))

        val next = IndexMerge.apply(base, delta, t, keyGeneration = 1)

        assertEquals(null, next.sessions.single { it.id == "s" }.deletedAt)
        assertEquals(listOf("o-s"), next.objects.map { it.name })
        assertTrue(next.sessions.none { it.id == "gone" })
    }

    @Test
    fun anotherPhonesChangeMadeAgainOnALaterIndexKeepsEverythingOfBoth() {
        val t = clock.now
        val read = VaultIndex(
            updatedAt = t, revision = 3,
            sessions = listOf(session("s1", at = t), session("s2", at = t), session("gone", at = t)),
            objects = listOf(obj("o-1", "s1"), obj("o-2", "s2"), obj("o-gone", "gone")),
        )
        val theirs = read.copy(
            revision = 4,
            lease = LeasePolicy.lease(DeviceIdentity("phone-b", "Phone B"), t),
            sessions = listOf(session("s1", at = t).copy(title = "Renamed on B"), session("s2", at = t, deletedAt = t), session("s3", at = t)),
            objects = listOf(obj("o-1", "s1"), obj("o-3", "s3")),
            settingsJson = "{\"synced\":true}",
        )
        val ours = read.copy(revision = 4, sessions = read.sessions + session("s4", at = t), objects = read.objects + obj("o-4", "s4"))

        val both = IndexMerge.apply(ours, IndexMerge.diff(read, theirs), t, keyGeneration = 1)

        val byId = both.sessions.associateBy { it.id }
        assertEquals(setOf("s1", "s2", "s3", "s4"), byId.keys)
        assertEquals("Renamed on B", byId.getValue("s1").title)
        assertEquals(t, byId.getValue("s2").deletedAt)
        assertEquals(setOf("o-1", "o-3", "o-4"), both.objects.map { it.name }.toSet())
        assertEquals("phone-b", both.lease!!.deviceId)
        assertEquals(theirs.settingsJson, both.settingsJson)
    }

    @Test
    fun aNewVersionOfAnEntryIsTakenOnlyWhileTheEntryIsStillThere() {
        val t = clock.now
        val base = VaultIndex(updatedAt = t, objects = listOf(obj("o-kept", "s1")))
        val newer = listOf(obj("o-kept", "s1"), obj("o-removed-meanwhile", "s1")).map { it.copy(keyGeneration = 2) }

        val next = IndexMerge.apply(base, IndexDelta(replaceEntries = newer.associateBy { it.entryKey }), t, keyGeneration = 2)

        assertEquals(listOf("o-kept"), next.objects.map { it.name })
        assertEquals(2, next.objects.single().keyGeneration)
    }

    @Test
    fun theEarliestDeletionDateIsKeptSoThe30DayCountNeverRestarts() {
        val t = clock.now
        val first = session("s1", at = t, deletedAt = t - 10 * day)
        val later = session("s1", at = t + day, deletedAt = t)
        assertEquals(t - 10 * day, IndexMerge.mergeSession(first, later).deletedAt)
        assertEquals(t - 10 * day, IndexMerge.mergeSession(later, first).deletedAt)
    }

    @Test
    fun aChatKeptInTheClaudeAccountStaysMarkedWhicheverPhoneIsNewer() {
        val t = clock.now
        val kept = session("s1", at = t).copy(claudeAccount = true)
        val later = session("s1", at = t + day)
        assertTrue(IndexMerge.mergeSession(kept, later).claudeAccount)
        assertTrue(IndexMerge.mergeSession(later, kept).claudeAccount)
        assertEquals(t + day, IndexMerge.mergeSession(kept, later).lastActivityAt)
    }

    @Test
    fun aRestoreKeepsTheClaudeAccountMarkWhicheverSideIsNewer() {
        val t = clock.now
        // Another phone marked the chat; this phone's restore has newer activity and no mark.
        val marked = VaultIndex(updatedAt = t, sessions = listOf(session("s1", at = t, deletedAt = t - day).copy(claudeAccount = true)))
        val restored = IndexMerge.apply(marked, IndexDelta(sessions = listOf(SessionChange.Restore(session("s1", at = t + day)))), t, 1)
        assertTrue(restored.sessions.single().claudeAccount)
        assertEquals(t + day, restored.sessions.single().lastActivityAt)

        // This phone restores a marked chat; the index has newer activity and no mark.
        val newer = VaultIndex(updatedAt = t, sessions = listOf(session("s2", at = t + day, deletedAt = t - day)))
        val restore = SessionChange.Restore(session("s2", at = t).copy(claudeAccount = true))
        val restoredMarked = IndexMerge.apply(newer, IndexDelta(sessions = listOf(restore)), t, 1).sessions.single()
        assertTrue(restoredMarked.claudeAccount)
        assertEquals(t + day, restoredMarked.lastActivityAt)
        assertEquals(null, restoredMarked.deletedAt)
    }

    @Test
    fun onlyARestoreClearsADeletion() {
        val t = clock.now
        val base = VaultIndex(updatedAt = t, sessions = listOf(session("s1", at = t, deletedAt = t - day)))
        val upsert = IndexMerge.apply(base, IndexDelta(sessions = listOf(SessionChange.Upsert(session("s1", at = t + day)))), t, 1)
        assertEquals(t - day, upsert.sessions.single().deletedAt)
        val restore = IndexMerge.apply(base, IndexDelta(sessions = listOf(SessionChange.Restore(session("s1", at = t + day)))), t, 1)
        assertEquals(null, restore.sessions.single().deletedAt)
        assertEquals(SessionStatus.OPEN, restore.sessions.single().status)
        assertEquals(base.revision + 1, restore.revision)
    }

    @Test
    fun anotherPhonesChangesArriveUnlessThisPhoneChangedTheSameChat() = runBlocking {
        val accounts = FakeAccounts(clock)
        val a = TestPhone(accounts, clock).apply {
            sessions += session("s1", at = clock.now)
            sessions += session("s2", at = clock.now)
        }
        a.engine.syncNow()
        val b = TestPhone(accounts, clock, deviceId = "phone-b", deviceName = "Phone B")
        b.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(setOf("s1", "s2"), b.sessions.map { it.id }.toSet())

        clock.advance(LeasePolicy.TTL_MS + 1)
        b.sessions[b.sessions.indexOfFirst { it.id == "s1" }] = b.sessions.first { it.id == "s1" }.copy(title = "Named on B")
        b.settings.update { it.copy(phoneChatDays = 90) }
        b.engine.syncNow()
        a.sessions[a.sessions.indexOfFirst { it.id == "s2" }] = a.sessions.first { it.id == "s2" }.copy(title = "Named on A")

        clock.advance(LeasePolicy.TTL_MS + 1)
        a.engine.syncNow()
        assertEquals("Named on B", a.sessions.first { it.id == "s1" }.title)
        assertEquals("Named on A", a.sessions.first { it.id == "s2" }.title)
        assertEquals(90, a.settings.settings.value.phoneChatDays)
        val index = a.remoteIndex()!!
        assertEquals("Named on B", index.sessions.first { it.id == "s1" }.title)
        assertEquals("Named on A", index.sessions.first { it.id == "s2" }.title)
    }

    @Test
    fun anotherPhonesWriteMadeOnTopOfThisPhonesKeepsBoth() = runBlocking {
        val accounts = FakeAccounts(clock)
        val phone = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now) }
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("Be brief.")
        phone.engine.syncNow()
        val first = phone.remoteIndex()!!

        // Right after this phone's next write, another phone records a conflict copy on top of it.
        val other = session("other-conflict", at = clock.now).copy(status = SessionStatus.CONFLICT_COPY, conflictOf = "s1")
        phone.drive.afterIndexWrite = {
            phone.drive.afterIndexWrite = null
            val current = RemoteIndex().decode(phone.cipher, phone.drive.named(RemoteIndex.NAME).single().bytes)
            val theirs = current.copy(revision = current.revision + 1, updatedAt = clock.now, sessions = current.sessions + other)
            phone.drive.named(RemoteIndex.NAME).single().bytes = RemoteIndex().encode(phone.cipher, theirs)
        }
        phone.homeFile("claude", ".claude/CLAUDE.md").apply {
            writeText("Be brief. Write tests.")
            setLastModified(lastModified() + 5_000)
        }
        clock.advance(60_000)
        phone.engine.syncNow()

        val index = phone.remoteIndex()!!
        assertEquals("ours, then theirs on top: nothing is written again", first.revision + 2, index.revision)
        assertTrue(index.sessions.any { it.id == "other-conflict" })
        val memory = index.objects.single { it.kind == ObjectKind.MEMORY }
        assertEquals(Codec.sha256("Be brief. Write tests.".toByteArray()), memory.sha256)
        assertEquals(1, phone.drive.named(RemoteIndex.NAME).size)
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
    }
}
