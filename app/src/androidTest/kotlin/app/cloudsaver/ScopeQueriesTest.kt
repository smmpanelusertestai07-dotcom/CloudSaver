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
}
