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
    fun theEarliestDeletionDateIsKeptSoThe30DayCountNeverRestarts() {
        val t = clock.now
        val first = session("s1", at = t, deletedAt = t - 10 * day)
        val later = session("s1", at = t + day, deletedAt = t)
        assertEquals(t - 10 * day, IndexMerge.mergeSession(first, later).deletedAt)
        assertEquals(t - 10 * day, IndexMerge.mergeSession(later, first).deletedAt)
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
    fun aWriteThatRacesAnotherPhoneIsAppliedOnTopOfTheirs() = runBlocking {
        val accounts = FakeAccounts(clock)
        val phone = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now) }
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("Be brief.")
        phone.engine.syncNow()
        val first = phone.remoteIndex()!!

        // Right after this phone's next write, another phone records a conflict copy of its own.
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
        assertEquals("their write, then ours on top", first.revision + 3, index.revision)
        assertTrue(index.sessions.any { it.id == "other-conflict" })
        val memory = index.objects.single { it.kind == ObjectKind.MEMORY }
        assertEquals(Codec.sha256("Be brief. Write tests.".toByteArray()), memory.sha256)
        assertEquals(1, phone.drive.named(RemoteIndex.NAME).size)
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
    }
}
