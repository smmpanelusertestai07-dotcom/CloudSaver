package com.pocketide.sync

import com.pocketide.google.DriveRevisions
import com.pocketide.google.DriveStore
import com.pocketide.model.Lease
import com.pocketide.model.VaultIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteIndexTest {
    private val clock = FakeClock()
    private val drive = FakeAccounts(clock)[TestPhone.OWNER]
    private val cipher = FakeCipher()
    private val remote = RemoteIndex()
    private val phoneB = LeasePolicy.lease(DeviceIdentity("phone-b", "Phone B"), clock.now)

    private fun stored(): VaultIndex = remote.decode(cipher, drive.named(RemoteIndex.NAME).single().bytes)

    /** Another phone's write: the index as it is in Drive now, changed by [change]. */
    private fun anotherPhoneWrites(change: (VaultIndex) -> VaultIndex) {
        val file = drive.named(RemoteIndex.NAME).single()
        val index = stored()
        file.bytes = remote.encode(cipher, change(index).copy(revision = index.revision + 1))
    }

    private fun addSession(id: String): (VaultIndex) -> VaultIndex = { it.copy(sessions = it.sessions + session(id, at = clock.now)) }

    /** This phone's write: one session record added to the newest index. */
    private suspend fun write(store: DriveStore, id: String, lease: (VaultIndex?) -> Lease? = { null }): RemoteSnapshot {
        val delta = IndexDelta(sessions = listOf(SessionChange.Upsert(session(id, at = clock.now))))
        return remote.commit(
            drive = store,
            cipher = cipher,
            start = RemoteSnapshot(null, null),
            requireLease = lease,
            change = { IndexMerge.apply(it, delta, clock.now, 1) },
            emptyIndex = { VaultIndex(updatedAt = clock.now) },
        )
    }

    private fun sessionIds() = stored().sessions.map { it.id }.toSet()

    @Test
    fun aWriteThatReplacedAnotherPhonesIsMadeAgainOnTopOfTheirs() = runBlocking {
        write(drive, "first")
        drive.beforeUpload = { name ->
            if (name == RemoteIndex.NAME) {
                drive.beforeUpload = null
                anotherPhoneWrites(addSession("theirs"))
            }
        }

        val result = write(drive, "ours")

        assertEquals(setOf("first", "theirs", "ours"), sessionIds())
        assertEquals(stored(), result.index)
        assertTrue("every write is newer than the one it replaced", stored().revision > 2)
    }

    @Test
    fun twoReplacedWritesInARowStillKeepEveryPhonesWrite() = runBlocking {
        write(drive, "first")
        val others = ArrayDeque(listOf("b", "c"))
        drive.beforeUpload = { name ->
            if (name == RemoteIndex.NAME) others.removeFirstOrNull()?.let { anotherPhoneWrites(addSession(it)) }
        }

        write(drive, "ours")

        assertEquals(setOf("first", "b", "c", "ours"), sessionIds())
    }

    @Test
    fun aLeaseTakenByTheReplacedWriteIsPutBackAndReported() = runBlocking {
        write(drive, "first")
        drive.beforeUpload = { name ->
            if (name == RemoteIndex.NAME) {
                drive.beforeUpload = null
                anotherPhoneWrites { it.copy(lease = phoneB, sessions = it.sessions + session("theirs", at = clock.now)) }
            }
        }

        try {
            write(drive, "ours") { index -> index?.lease?.takeIf { it.deviceId == phoneB.deviceId } }
            fail("the lease was taken")
        } catch (lost: LeaseLostException) {
            assertEquals(phoneB, lost.holder)
            assertEquals(stored(), lost.snapshot.index)
        }
        assertEquals("theirs is back, without ours", setOf("first", "theirs"), sessionIds())
        assertEquals(phoneB, stored().lease)
    }

    @Test
    fun aStoreThatCannotListVersionsWritesAgainWhenDriveHoldsAnotherIndex() = runBlocking {
        val plain = object : DriveStore by drive {
            override val revisions: DriveRevisions? = null
        }
        write(plain, "first")
        drive.afterIndexWrite = {
            drive.afterIndexWrite = null
            anotherPhoneWrites { it.copy(sessions = it.sessions.filter { s -> s.id != "ours" } + session("theirs", at = clock.now)) }
        }

        write(plain, "ours")

        assertEquals(setOf("first", "theirs", "ours"), sessionIds())
    }
}
