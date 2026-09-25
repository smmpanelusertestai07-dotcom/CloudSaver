package com.pocketide.sync

import com.pocketide.model.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phoneA() = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }
    private fun phoneB() = TestPhone(accounts, clock, deviceId = "phone-b", deviceName = "Phone B")

    @Test
    fun theHolderRenewsItsLeaseAndASecondPhoneIsAskedFirst() = runBlocking {
        val a = phoneA()
        a.homeFile("claude", path).writeText("hi\n")
        a.engine.syncNow()
        val lease = a.remoteIndex()!!.lease!!
        assertEquals("phone-a", lease.deviceId)
        assertEquals(clock.now + LeasePolicy.TTL_MS, lease.expiresAt)

        val b = phoneB()
        b.engine.syncNow()
        assertEquals("Phone A", b.engine.leaseHolder.value)
        assertEquals("phone-a", b.remoteIndex()!!.lease!!.deviceId)

        clock.advance(30 * Durations.MINUTE)
        a.engine.syncNow()
        assertEquals("a heartbeat on each sync", clock.now + LeasePolicy.TTL_MS, a.remoteIndex()!!.lease!!.expiresAt)
        assertNull(a.engine.leaseHolder.value)
    }

    @Test
    fun anExpiredLeaseIsTakenWithoutAsking() = runBlocking {
        val a = phoneA()
        a.homeFile("claude", path).writeText("hi\n")
        a.engine.syncNow()
        clock.advance(LeasePolicy.TTL_MS + 1)
        val b = phoneB()
        b.engine.syncNow()
        assertNull(b.engine.leaseHolder.value)
        assertEquals("phone-b", b.remoteIndex()!!.lease!!.deviceId)
    }

    @Test
    fun useHereTakesTheLeaseAndTheFirstPhoneKeepsItsWorkAsConflictCopies() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("shared start\n")
        a.engine.syncNow()

        // Phone A keeps working offline while the owner picks up phone B.
        a.drive.offline = true
        file.appendText("written on A while offline\n")
        clock.advance(5 * Durations.MINUTE)
        a.engine.syncNow()
        assertEquals(1, a.queued().size)
        a.drive.offline = false

        val b = phoneB()
        b.engine.syncNow()
        assertEquals("Phone A", b.engine.leaseHolder.value)
        b.engine.takeOver()
        assertNull(b.engine.leaseHolder.value)
        assertEquals("phone-b", b.remoteIndex()!!.lease!!.deviceId)

        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        assertEquals("Phone B", a.engine.leaseHolder.value)
        assertTrue(a.notifier.posted.any { it.key == "lease" })

        val index = a.remoteIndex()!!
        assertEquals("phone-b", index.lease!!.deviceId)
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        assertEquals("s1", copy.conflictOf)
        assertNotEquals("s1", copy.id)
        val copied = index.objects.filter { it.sessionId == copy.id }
        assertEquals(1, copied.size)
        assertEquals(file.length(), copied.single().length)
        assertTrue(a.queued().isEmpty())

        // Phone A stops syncing: new work stays on the phone.
        file.appendText("more on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        assertEquals(index.revision, a.remoteIndex()!!.revision)

        // Phone B sees the conflict copy in its chats.
        b.engine.syncNow()
        assertTrue(b.adopted.any { it.id == copy.id && it.status == SessionStatus.CONFLICT_COPY })
        b.engine.fetchSession(copy.id)
        val restored = copied.single().path
        assertEquals(file.readText().removeSuffix("more on A\n"), b.homeFile("claude", restored).readText())
    }

    @Test
    fun theDailyJobBringsInTheOtherPhonesWorkBeforeItTakesAnExpiredLease() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        val b = phoneB()
        b.engine.fetchSession("s1")
        b.engine.takeOver()
        b.homeFile("claude", path).appendText("continued on B\n")
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()

        // Phone B goes quiet; its lease runs out and phone A's daily job runs first.
        clock.advance(LeasePolicy.TTL_MS + 1)
        a.engine.runMaintenance()
        a.engine.syncNow()
        assertEquals("start\ncontinued on B\n", file.readText())

        file.appendText("continued on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.fetchSession("s1")
        assertEquals("start\ncontinued on B\ncontinued on A\n", reader.homeFile("claude", path).readText())
        assertTrue(a.remoteIndex()!!.sessions.none { it.status == SessionStatus.CONFLICT_COPY })
    }

    @Test
    fun aWriteOnTopOfWorkThisPhoneHasNotBroughtInLeavesItForTheNextSync() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        val b = phoneB()
        b.engine.fetchSession("s1")
        b.engine.takeOver()
        b.homeFile("claude", path).appendText("continued on B\n")
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()
        clock.advance(LeasePolicy.TTL_MS + 1)

        // A write that does not reconcile first, as the daily job's once did, takes the expired lease.
        val kit = SyncKit(a)
        val run = Run(kit, a.cipher)
        val drive = run.drive()
        Committer(kit).commit(run, drive, kit.remote.fetch(drive, run.cipher, run.state.remote, run.index), CommitMode.HOLDER)
        assertEquals("phone-a", a.remoteIndex()!!.lease!!.deviceId)

        a.engine.syncNow()
        assertEquals("start\ncontinued on B\n", file.readText())
    }

    @Test
    fun aRunningRoomsChatIsRewrittenOnlyOnceTheRoomStops() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        val b = phoneB()
        b.engine.fetchSession("s1")
        b.engine.takeOver()
        b.homeFile("claude", path).appendText("continued on B\n")
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()

        // Phone A's agent still runs when A takes the lease back, and it keeps writing the chat.
        a.runningRooms += "claude"
        clock.advance(Durations.MINUTE)
        a.engine.takeOver()
        assertEquals("start\n", file.readText())
        file.appendText("continued on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        assertEquals("start\ncontinued on A\n", file.readText())
        assertTrue("nothing is sent against the version Drive replaced", a.queued().none { it.path == path })
        assertEquals(listOf("start\n".length.toLong()), a.remoteIndex()!!.objects.filter { it.path == path && it.offset > 0 }.map { it.offset })

        a.runningRooms.clear()
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        assertEquals("start\ncontinued on B\n", file.readText())
        val index = a.remoteIndex()!!
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.fetchSession(copy.id)
        val copied = index.objects.single { it.sessionId == copy.id }
        assertEquals("start\ncontinued on A\n", reader.homeFile("claude", copied.path).readText())
    }

    @Test
    fun aPhoneThatTakesTheLeaseBackContinuesFromDrive() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        val b = phoneB()
        b.engine.fetchSession("s1")
        b.engine.takeOver()
        val onB = b.homeFile("claude", path)
        onB.appendText("continued on B\n")
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()

        clock.advance(Durations.MINUTE)
        a.engine.takeOver()
        assertEquals("start\ncontinued on B\n", file.readText())
        assertEquals(file.length(), a.state().tracks.values.single { it.path == path }.syncedLength)
        assertTrue(a.remoteIndex()!!.sessions.none { it.status == SessionStatus.CONFLICT_COPY })
    }
}
