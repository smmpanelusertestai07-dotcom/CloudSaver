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
