package app.entesaver.media

import app.entesaver.core.logic.ItemState
import app.entesaver.data.db.ItemRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A clip that runs out of time waits for a longer run instead of going out
 * full size. On a phone whose runs never get a foreground service that longer
 * run never comes, and the clip used to be started first in every run, for
 * ever, holding up every file behind it. Each wait is a counted try now, and
 * the third sets the clip aside: the original stays, and no copy is made.
 */
class OutOfTimeStrikesTest {

    private val clip = ItemRow(
        id = 7,
        fingerprint = "fp-clip.mp4",
        displayName = "clip.mp4",
        sizeBytes = 400_000_000,
        dateModified = 1,
        captureAt = 1_000,
        durationMs = 4 * 60_000L,
        mimeType = "video/mp4",
        isVideo = true,
        state = ItemState.NEW.name
    )

    @Test
    fun `three runs out of time set the clip aside, original kept and no copy`() {
        var row = clip
        repeat(Stager.MAX_TRIES - 1) { n ->
            row = Stager.struck(row, Stager.OUT_OF_TIME, Stager.OUT_OF_TIME, now = n.toLong())
            assertEquals("still waiting after try ${n + 1}", ItemState.NEW.name, row.state)
            // The mark the plain-run query holds the clip back by.
            assertEquals(Stager.OUT_OF_TIME, row.lastError)
        }
        row = Stager.struck(row, Stager.OUT_OF_TIME, Stager.OUT_OF_TIME, now = 9)
        assertEquals(ItemState.SKIP.name, row.state)
        assertEquals(Stager.OUT_OF_TIME, row.skipReason)
        assertEquals(Stager.MAX_TRIES, row.attempts)
        // Settled without a copy: nothing staged, nothing to release.
        assertNull(row.stagePath)
        assertNull(row.outputBytes)
        assertEquals(false, row.originalMissing)
    }

    @Test
    fun `a plain run takes an overrun clip only when nothing else is left`() {
        val worker = File("src/main/kotlin/app/entesaver/work/CompressWorker.kt").readText()
        assertTrue(worker.contains("holdOverrun = !foreground"))
        assertTrue(worker.contains(".ifEmpty {"))
        val dao = File("src/main/kotlin/app/entesaver/data/db/Db.kt").readText()
        assertTrue(dao.contains("lastError != 'out_of_time'"))
        assertEquals("the code the query holds back by", "out_of_time", Stager.OUT_OF_TIME)
    }
}
