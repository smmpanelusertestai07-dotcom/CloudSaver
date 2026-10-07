package app.entesaver

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.entesaver.core.logic.Evidence
import app.entesaver.core.logic.GoneReason
import app.entesaver.core.logic.ItemState
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.data.db.leftFolderAtAfter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which copies count as having left the folder during a copy's window.
 *
 * Paced proof credits Ente's traffic to one copy only when no other copy of
 * ours could have sent any of it, and a copy that left the folder during the
 * window could have. Reading that from a row's last change let unrelated
 * bookkeeping block a copy that really was alone; reading it only while the
 * row kept its release time missed a copy sent back to the queue, whose
 * release time is cleared - and its bytes were credited to the copy beside
 * it. So every way out of RELEASED is written here as the app writes it, and
 * the query run against a real database.
 */
@RunWith(AndroidJUnit4::class)
class LeftFolderQueryTest {

    private lateinit var db: AppDb

    /** B's window opens here. */
    private val since = 10_000L
    private val leftAt = 12_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDb::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun released(name: String, releasedAt: Long = 9_000L): ItemRow {
        val row = ItemRow(
            fingerprint = "fp-$name",
            displayName = "$name.jpg",
            sizeBytes = 4_000_000,
            dateModified = 1,
            captureAt = 1,
            mimeType = "image/jpeg",
            isVideo = false,
            state = ItemState.RELEASED.name,
            outputUri = "content://media/external/images/media/$name",
            outputName = "$name.jpg",
            outputBytes = 1_000_000,
            releasedAt = releasedAt,
            updatedAt = releasedAt
        )
        val id = db.items().insert(row)
        return row.copy(id = id)
    }

    /** Writes [row] as the exit path did, and returns its id. */
    private suspend fun leave(row: ItemRow): Long {
        db.items().update(row)
        return row.id
    }

    @Test
    fun everyWayOutOfTheFolderIsRead() = runBlocking {
        val now = leftAt
        val expected = mutableMapOf<String, Long>()

        // detectGone: a graded copy that vanished.
        released("userDeleted").let {
            expected["userDeleted"] = leave(
                it.copy(
                    state = ItemState.GONE.name, goneReason = GoneReason.USER_DELETED.name,
                    evidence = Evidence.VERIFIED.name, leftFolderAt = now, updatedAt = now
                )
            )
        }
        // detectGone: our own deletion seen.
        released("appDeleted").let {
            expected["appDeleted"] = leave(
                it.copy(
                    state = ItemState.GONE.name, goneReason = GoneReason.APP_DELETED.name,
                    leftFolderAt = now, updatedAt = now
                )
            )
        }
        // detectGone: per-file proof as it went - granted inside the window.
        released("confirmed").let {
            expected["confirmed"] = leave(
                it.copy(
                    state = ItemState.GONE.name, goneReason = GoneReason.CONFIRMED.name,
                    evidence = Evidence.CONFIRMED_EXACT.name, confirmedAt = now,
                    leftFolderAt = now, updatedAt = now
                )
            )
        }
        // detectGone: RESEND with nothing to re-make it from, or already in
        // the ledger.
        released("done").let {
            expected["done"] = leave(it.copy(state = ItemState.DONE.name, leftFolderAt = now, updatedAt = now))
        }
        // detectGone: GIVE_UP.
        released("gaveUp").let {
            expected["gaveUp"] = leave(
                it.copy(
                    state = ItemState.SKIP.name, skipReason = "removed_before_upload",
                    outputUri = null, leftFolderAt = now, updatedAt = now
                )
            )
        }
        // detectGone: RESEND - back to the queue, its release time cleared.
        released("resent").let {
            expected["resent"] = leave(
                it.copy(
                    state = ItemState.NEW.name, evidence = Evidence.NONE.name,
                    goneReason = GoneReason.USER_DELETED.name, outputUri = null,
                    releasedAt = null, leftFolderAt = now, batchId = null,
                    resendCount = it.resendCount + 1, updatedAt = now
                )
            )
        }
        // repairStalePending: back to the queue, its release time cleared.
        released("stalePending").let {
            expected["stalePending"] = leave(
                it.copy(
                    state = ItemState.NEW.name, outputUri = null, releasedAt = null,
                    leftFolderAt = now, updatedAt = now
                )
            )
        }
        // ReclaimEngine.settleFreed, and the other writers that do not know
        // what they found: the helper stamps the leave.
        released("freed").let {
            expected["freed"] = leave(
                it.copy(
                    state = ItemState.FREED.name,
                    leftFolderAt = it.leftFolderAtAfter(ItemState.FREED.name, now),
                    updatedAt = now
                )
            )
        }
        released("lazyDeleted").let {
            expected["lazyDeleted"] = leave(
                it.copy(
                    state = ItemState.DONE.name, goneReason = GoneReason.APP_DELETED.name,
                    outputUri = null,
                    leftFolderAt = it.leftFolderAtAfter(ItemState.DONE.name, now),
                    updatedAt = now
                )
            )
        }
        // Leaving the moment B went out is inside its window too.
        released("atSince").let {
            expected["atSince"] = leave(
                it.copy(state = ItemState.GONE.name, leftFolderAt = since, updatedAt = since)
            )
        }

        val left = db.items().leftReleasedSince(since).associateBy { it.id }
        for ((name, id) in expected) {
            assertTrue("$name left during the window and must be read", id in left)
        }
        assertEquals(expected.size, left.size)
        for ((name, id) in expected) {
            val want = if (name == "atSince") since else now
            assertEquals("$name is dated by when it left", want, left.getValue(id).leftAt)
        }
        // Per-file proof is handed back with its time, so the rule can tell
        // proof before the window from proof inside it.
        val confirmed = left.getValue(expected.getValue("confirmed"))
        assertEquals(Evidence.CONFIRMED_EXACT.name, confirmed.evidence)
        assertEquals(now, confirmed.confirmedAt)
    }

    @Test
    fun aCopySentBackToTheQueueIsStillReadOnceItsReleaseTimeIsGone() = runBlocking {
        // The shipped miss: N went back to the queue mid-window with its
        // release time cleared, and B was credited with N's bytes.
        val n = released("n")
        db.items().update(
            n.copy(
                state = ItemState.NEW.name, outputUri = null, releasedAt = null,
                leftFolderAt = leftAt, resendCount = 1, updatedAt = leftAt
            )
        )
        assertEquals(listOf(n.id), db.items().leftReleasedSince(since).map { it.id })
        // Staged again later, and still read until it has been released
        // again - when it is back in the folder and counts there instead.
        db.items().update(
            db.items().byId(n.id)!!.copy(state = ItemState.STAGED.name, updatedAt = leftAt + 1)
        )
        assertEquals(leftAt, db.items().leftReleasedSince(since).single().leftAt)
        db.items().update(
            db.items().byId(n.id)!!.copy(
                state = ItemState.RELEASED.name, releasedAt = leftAt + 2, updatedAt = leftAt + 2
            )
        )
        assertTrue(db.items().leftReleasedSince(since).isEmpty())
    }

    @Test
    fun aLaterWriteToALongGoneCopyDoesNotMoveWhenItLeft() = runBlocking {
        // Left long before B's window, then touched inside it - the original
        // deleted in the gallery, a reclaim settle, a location refresh.
        val old = released("old", releasedAt = 1_000)
        db.items().update(
            old.copy(state = ItemState.DONE.name, leftFolderAt = 5_000, updatedAt = 5_000)
        )
        db.items().update(
            db.items().byId(old.id)!!.copy(originalMissing = true, updatedAt = 20_000)
        )
        assertTrue(
            "unrelated bookkeeping must not block a copy that really was alone",
            db.items().leftReleasedSince(since).isEmpty()
        )
        // Even once its release time is gone.
        db.items().update(db.items().byId(old.id)!!.copy(releasedAt = null, updatedAt = 21_000))
        assertTrue(db.items().leftReleasedSince(since).isEmpty())
    }

    @Test
    fun aCopyThatLeftBeforeTheLeaveWasRecordedIsReadAsBefore() = runBlocking {
        // Left under 12.1: no leave time, its release time kept, last
        // written inside the window - read by that write, as it always was.
        val legacy = released("legacy", releasedAt = 1_000)
        db.items().update(legacy.copy(state = ItemState.GONE.name, leftFolderAt = null, updatedAt = 15_000))
        // Last written before the window: not read.
        val legacyOld = released("legacyOld", releasedAt = 1_000)
        db.items().update(legacyOld.copy(state = ItemState.DONE.name, leftFolderAt = null, updatedAt = 9_000))
        // Never released at all, written inside the window: not a copy that
        // ever was in the folder.
        db.items().insert(
            ItemRow(
                fingerprint = "fp-queued", displayName = "queued.jpg", sizeBytes = 1,
                dateModified = 1, captureAt = 1, mimeType = "image/jpeg", isVideo = false,
                state = ItemState.NEW.name, updatedAt = 15_000
            )
        )
        // Still in the folder: it is waiting, not gone.
        released("waiting", releasedAt = 11_000)

        val left = db.items().leftReleasedSince(since)
        assertEquals(listOf(legacy.id), left.map { it.id })
        assertEquals(15_000L, left.single().leftAt)
    }
}
