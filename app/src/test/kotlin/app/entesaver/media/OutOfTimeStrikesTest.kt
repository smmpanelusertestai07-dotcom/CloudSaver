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
 * ever, holding up every file behind it. A wait in a run with a foreground
 * service is a counted try now, and the third sets the clip aside. A plain
 * run - the day's foreground allowance spent, say - only holds it back, for
 * a week at most. Either way the original stays, and no copy is made.
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
        assertTrue(dao.contains("lastError NOT GLOB 'out_of_time*'"))
        assertEquals("the code the query holds back by", "out_of_time", Stager.OUT_OF_TIME)
    }

    @Test
    fun `a plain run holds the clip back without a try, for a week at most`() {
        val day = 24 * 60 * 60_000L
        val start = 1_000_000_000L
        // Tomorrow's run with the whole budget may well finish it.
        var row = Stager.heldBack(clip, start)
        assertEquals(ItemState.NEW.name, row.state)
        assertEquals("no try counted", 0, row.attempts)
        assertTrue("held back like a counted try", Stager.overran(row.lastError))
        // Every later plain overrun keeps the first one's date.
        repeat(6) { n ->
            row = Stager.heldBack(row, start + (n + 1) * day)
            assertEquals(ItemState.NEW.name, row.state)
            assertEquals(0, row.attempts)
        }
        // A phone that never gives the whole budget still settles the clip:
        // set aside, original kept, no copy.
        row = Stager.heldBack(row, start + Stager.PLAIN_OVERRUN_LIMIT_MS)
        assertEquals(ItemState.SKIP.name, row.state)
        assertEquals(Stager.OUT_OF_TIME, row.skipReason)
        assertNull(row.stagePath)
        assertNull(row.outputBytes)
        assertEquals(false, row.originalMissing)
    }

    @Test
    fun `a counted try or a clock set back starts the week again`() {
        val day = 24 * 60 * 60_000L
        val held = Stager.heldBack(clip, 10 * day)
        // A run with a foreground service counted a try in between.
        val struck = Stager.struck(held, Stager.OUT_OF_TIME, Stager.OUT_OF_TIME, now = 11 * day)
        assertEquals(ItemState.NEW.name, Stager.heldBack(struck, 17 * day + 1).state)
        // The clock went back: the mark's date is ahead of now.
        val back = Stager.heldBack(held, 2 * day)
        assertEquals(ItemState.NEW.name, back.state)
        assertEquals(ItemState.NEW.name, Stager.heldBack(back, 8 * day).state)
        assertEquals(ItemState.SKIP.name, Stager.heldBack(back, 9 * day).state)
    }

    @Test
    fun `only a run with a foreground service counts the try`() {
        val worker = File("src/main/kotlin/app/entesaver/work/CompressWorker.kt").readText()
        assertTrue(worker.contains("overrunCounts = foreground"))
        val stager = File("src/main/kotlin/app/entesaver/media/Stager.kt").readText()
        val caught = stager.substringAfter("catch (late: VideoCompressor.OutOfTime)")
            .substringBefore("catch (e: Exception)")
        assertTrue(caught.contains("if (overrunCounts)"))
        assertTrue(caught.contains("heldBack(cur, now)"))
    }

    @Test
    fun `asking for a clip first clears what held it back`() {
        for (mark in listOf(Stager.OUT_OF_TIME, Stager.heldBack(clip, 5).lastError)) {
            val struck = clip.copy(
                state = ItemState.SKIP.name, skipReason = Stager.OUT_OF_TIME,
                attempts = Stager.MAX_TRIES, lastError = mark
            )
            val asked = Stager.askedFirst(struck, now = 99)
            assertEquals(ItemState.NEW.name, asked.state)
            assertEquals(0, asked.attempts)
            assertNull(asked.skipReason)
            assertEquals(99L, asked.priorityAt)
            assertEquals("the capture date stays", clip.captureAt, asked.captureAt)
            assertEquals("a plain run's queue would put it last: $mark", false, Stager.overran(asked.lastError))
        }
        // Any other note stays.
        assertEquals("encode_failed", Stager.askedFirst(clip.copy(lastError = "encode_failed"), 1).lastError)
    }
}
