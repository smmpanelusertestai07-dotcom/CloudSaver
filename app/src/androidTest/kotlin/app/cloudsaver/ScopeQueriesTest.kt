package app.cloudsaver

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scope clause, held to on the queries that promise future work.
 *
 * The database inventories the whole phone on purpose - returned copies,
 * duplicates and presence all need it - but every number that says "this is
 * waiting" or "the trial will use this" must count ticked albums only. The
 * shipped bug: one album ticked with one photo in it, and the trial optimised
 * three photos from albums the user had just declined to hand over, because
 * its pick read the inventory rather than the choice.
 */
@RunWith(AndroidJUnit4::class)
class ScopeQueriesTest {

    private lateinit var db: AppDb

    private fun photo(name: String, bucket: String?, captureAt: Long) = ItemRow(
        fingerprint = "fp-$name",
        displayName = name,
        sizeBytes = 1_000,
        dateModified = captureAt / 1000,
        captureAt = captureAt,
        mimeType = "image/jpeg",
        isVideo = false,
        bucket = bucket,
        state = "NEW"
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDb::class.java
        ).build()
        runBlocking {
            // Three albums and an SD-card row with no bucket at all; only
            // Screenshots is ticked. Camera holds the newest photos, exactly
            // the shape of the phone the bug shipped on.
            db.items().insert(photo("cam1.jpg", "Camera", 5_000))
            db.items().insert(photo("cam2.jpg", "Camera", 4_000))
            db.items().insert(photo("dl1.jpg", "Download", 3_000))
            db.items().insert(photo("shot1.jpg", "Screenshots", 2_000))
            db.items().insert(photo("nobucket.jpg", null, 1_000))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val everythingButScreenshots = setOf("Camera", "Download")

    @Test
    fun theTrialPicksOnlyFromTickedAlbums() = runBlocking {
        val picked = db.items().newestNewPhotos(3, everythingButScreenshots)
        // Two eligible rows exist - the ticked album's photo and the row no
        // album claims - and newest-first order must survive the clause.
        assertEquals(listOf("shot1.jpg", "nobucket.jpg"), picked.map { it.displayName })
    }

    @Test
    fun theTrialCountAgreesWithTheTrialPick() = runBlocking {
        assertEquals(2, db.items().waitingPhotoCountFlow(everythingButScreenshots).first())
        // Nothing excluded: the whole inventory is the queue.
        assertEquals(5, db.items().waitingPhotoCountFlow(emptySet<String>()).first())
    }

    @Test
    fun waitingCountsFollowTheTicks() = runBlocking {
        assertEquals(2, db.items().newInScopeCount(everythingButScreenshots))
        assertEquals(2, db.items().newInScopeCountFlow(everythingButScreenshots).first())
        assertEquals(5, db.items().newInScopeCount(emptySet<String>()))
    }

    @Test
    fun theProjectionCoversOnlyWhatARunMayTouch() = runBlocking {
        assertEquals(
            2_000,
            db.items().pendingBytesByType(video = false, everythingButScreenshots)
        )
        assertEquals(2, db.items().pendingCountByType(video = false, everythingButScreenshots))
        assertEquals(0, db.items().pendingCountByType(video = true, everythingButScreenshots))
    }

    /**
     * The list agrees with the counter, on a library big enough to disagree.
     *
     * The Files query is capped, and the worker takes newest first, so on a
     * mature phone the newest rows are all finished work and the backlog is
     * older. The filter used to run in Kotlin over the capped result: tapping
     * "Waiting" took the newest 500 rows - every one of them DONE - kept
     * none, and printed "No files match these filters" while Home's counter,
     * a COUNT over the same table, said six hundred. Six hundred waiting
     * files, none of them listed.
     *
     * 600 NEW rows older than 500 DONE ones reproduces exactly that.
     */
    @Test
    fun theWaitingListIsNotEmptiedByRowsItDidNotAskFor() = runBlocking {
        val setUpInScopeNew = db.items().newInScopeCount(everythingButScreenshots)
        val done = 500
        val waiting = 600
        for (i in 0 until done) {
            db.items().insert(
                photo("done_$i.jpg", "Screenshots", 1_000_000L + i).copy(
                    fingerprint = "fp-done-$i", state = "DONE"
                )
            )
        }
        for (i in 0 until waiting) {
            db.items().insert(
                photo("wait_$i.jpg", "Screenshots", 100_000L + i).copy(
                    fingerprint = "fp-wait-$i", state = "NEW"
                )
            )
        }

        val counter = db.items().newInScopeCount(everythingButScreenshots)
        val listed = db.items().searchFlow(
            q = "", states = listOf("NEW"), anyState = 0,
            excludedBuckets = everythingButScreenshots, sortKey = 0, limit = 500
        ).first()

        // The counter sees every waiting row, plus whatever setUp left in
        // scope - measured, not assumed. setUp puts two NEW rows inside the
        // scope, not one: shot1.jpg in the ticked album, and nobucket.jpg,
        // which has no album at all and so is never excluded by an album tick.
        // Writing "+ 1" here turned eight emulator jobs red for a fault that
        // was in this line and nowhere else.
        assertEquals("in-scope NEW rows from setUp", 2, setUpInScopeNew)
        assertEquals(waiting + setUpInScopeNew, counter)
        // The list is capped, but it is capped on the answer - so it is full
        // of the rows the chip asked for, not empty of them.
        assertEquals(500, listed.size)
        assertTrue(
            "every listed row must be a waiting row, not the DONE rows that " +
                "used to fill the cap: ${listed.map { it.state }.distinct()}",
            listed.all { it.state == "NEW" }
        )
    }

    /**
     * "Largest" means largest of what was asked for, not largest of the
     * newest page. Sorting after a cap sorts the wrong five hundred.
     */
    @Test
    fun theLargestSortReachesPastTheNewestPage() = runBlocking {
        // One big old file, buried under 600 newer small ones.
        db.items().insert(
            photo("old_whale.mp4", "Screenshots", 1L).copy(
                fingerprint = "fp-whale", sizeBytes = 9_000_000_000L, isVideo = true
            )
        )
        for (i in 0 until 600) {
            db.items().insert(
                photo("new_$i.jpg", "Screenshots", 2_000_000L + i).copy(fingerprint = "fp-new-$i")
            )
        }
        val bySize = db.items().searchFlow(
            q = "", states = emptyList(), anyState = 1,
            excludedBuckets = everythingButScreenshots, sortKey = 2, limit = 500
        ).first()
        assertEquals("the biggest file on the phone must head the list", "old_whale.mp4", bySize.first().displayName)
    }
}
