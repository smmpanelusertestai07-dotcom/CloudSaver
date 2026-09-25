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
    fun aTakeoverWhileThisPhoneUploadsIsNeverWrittenOver() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()
        val b = phoneB().apply { sessions += session("s2", at = clock.now, ref = "d00dfeed-2222") }
        val onB = b.homeFile("claude", claudeTranscript("owner/app", "s2", "d00dfeed-2222"))
        onB.writeText("a new chat on B\n")
        b.engine.syncNow()
        assertEquals("Phone A", b.engine.leaseHolder.value)

        // While phone A sends its next piece, the owner taps "Use here?" on phone B.
        var raced = false
        a.drive.beforeUpload = { name ->
            if (name.startsWith("o-") && !raced) {
                raced = true
                runBlocking { b.engine.takeOver() }
            }
        }
        file.appendText("more on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()
        a.drive.beforeUpload = null

        val index = a.remoteIndex()!!
        assertEquals("phone-b", index.lease!!.deviceId)
        assertEquals("Phone B", a.engine.leaseHolder.value)
        val chat = index.objects.filter { it.sessionId == "s2" }
        assertEquals("phone B's new chat is in Drive", onB.length(), Chains.length(Chains.chain(index.objects, chat.first().fileKey)))
        assertTrue(index.sessions.any { it.id == "s2" })
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        assertEquals("phone A's work is a conflict copy", file.length(), index.objects.single { it.sessionId == copy.id }.length)
        assertTrue(a.queued().isEmpty())
    }

    @Test
    fun anotherPhonesWriteBetweenTheReadAndTheWriteIsKeptUnderThisPhones() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        // Just before phone A's next index write reaches Drive, a locked phone records its conflict copy.
        val theirs = session("s1-conflict-b", at = clock.now).copy(status = SessionStatus.CONFLICT_COPY, conflictOf = "s1")
        val theirPiece = a.remoteIndex()!!.objects.single()
            .copy(name = "o-theirstheirstheirstheirs00", sessionId = theirs.id, path = ".pocketide/conflicts/b/$path")
        a.drive.beforeUpload = { name ->
            if (name == RemoteIndex.NAME) {
                a.drive.beforeUpload = null
                a.rewriteRemoteIndex { it.copy(revision = it.revision + 1, sessions = it.sessions + theirs, objects = it.objects + theirPiece) }
            }
        }
        file.appendText("more on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()

        val index = a.remoteIndex()!!
        assertTrue("the other phone's write is kept", index.sessions.any { it.id == theirs.id })
        assertTrue(index.objects.any { it.name == theirPiece.name })
        assertEquals("and phone A's is made on top of it", listOf(0L, 6L), index.objects.filter { it.path == path }.map { it.offset }.sorted())
        assertEquals("phone-a", index.lease!!.deviceId)
        assertTrue(a.queued().isEmpty())
        assertTrue(a.engine.status.value is SyncStatus.UpToDate)
    }

    @Test
    fun aTakeoverBetweenTheReadAndTheWriteIsPutBackAndThisPhonesWorkBecomesAConflictCopy() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("start\n")
        a.engine.syncNow()

        a.drive.beforeUpload = { name ->
            if (name == RemoteIndex.NAME) {
                a.drive.beforeUpload = null
                a.rewriteRemoteIndex { it.copy(revision = it.revision + 1, lease = LeasePolicy.lease(DeviceIdentity("phone-b", "Phone B"), clock.now)) }
            }
        }
        file.appendText("more on A\n")
        clock.advance(Durations.MINUTE)
        a.engine.syncNow()

        val index = a.remoteIndex()!!
        assertEquals("phone-b", index.lease!!.deviceId)
        assertEquals("Phone B", a.engine.leaseHolder.value)
        assertEquals("the chat in Drive is as phone B took it", listOf(0L), index.objects.filter { it.path == path }.map { it.offset })
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        assertEquals(file.length(), index.objects.single { it.sessionId == copy.id }.length)
        assertTrue(a.queued().isEmpty())
    }

    @Test
    fun aConflictCopyWhoseFilesWereSweptWhileItsPhoneWasOffIsSentAgainNotRecordedMissing() = runBlocking {
        val a = phoneA()
        val file = a.homeFile("claude", path)
        file.writeText("shared start\n")
        a.engine.syncNow()
        val b = phoneB()
        b.engine.fetchSession("s1")
        b.engine.takeOver()

        // Phone B keeps working offline while phone A takes the lease back.
        b.network.online = false
        b.homeFile("claude", path).appendText("written on B offline\n")
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()
        a.engine.takeOver()

        // Back online, B uploads its work as a conflict copy, but dies before recording it.
        b.network.online = true
        b.drive.failIndexWrites = 1
        clock.advance(Durations.MINUTE)
        b.engine.syncNow()
        assertTrue(b.queued().any { it.conflict && it.driveId != null })

        // B stays off longer than the sweep's grace; A's daily job removes files no index names.
        clock.advance(Maintenance.ORPHAN_GRACE_MS + Durations.DAY)
        a.engine.runMaintenance()
        repeat(2) { b.engine.syncNow() }

        val index = a.remoteIndex()!!
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.fetchSession(copy.id)
        val copied = index.objects.single { it.sessionId == copy.id }
        assertEquals("shared start\nwritten on B offline\n", reader.homeFile("claude", copied.path).readText())
        assertTrue(b.queued().isEmpty())
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
