package app.entesaver.util

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free up's turn at the encoder: asked for once a batch, a short wait at
 * most, and the background run gives way while it is wanted.
 *
 * A remake used to wait on Locks.stage row by row with no limit, behind a
 * background run that took the lock back between rows - one more encode,
 * up to twenty minutes, for every row, on a bare spinner.
 */
class StageTurnTest {

    @After
    fun reset() {
        if (Locks.stage.isLocked) Locks.stage.unlock()
        Locks.runYieldsUntil.set(0L)
        Locks.freeUpWaiting.set(0)
        Locks.runClock = { 0L }
    }

    @Test
    fun `a free encoder is taken once and kept for the whole batch`() = runBlocking {
        val turn = StageTurn(waitMs = 1_000L, yieldMs = 60_000L)
        assertTrue(turn.take())
        assertTrue("held for the batch", Locks.stage.isLocked)
        assertTrue("the run starts no new file meanwhile", Locks.runShouldYield())
        assertTrue("a later row needs no second ask", turn.take())
        turn.close()
        assertFalse(Locks.stage.isLocked)
        assertFalse("the run carries on once the batch is done", Locks.runShouldYield())
        assertEquals(0, turn.refusals)
    }

    @Test
    fun `a batch with no remake never asks and never holds the run up`() {
        val turn = StageTurn(waitMs = 1_000L, yieldMs = 60_000L)
        turn.close()
        assertFalse(Locks.stage.isLocked)
        assertFalse(Locks.runShouldYield())
    }

    @Test
    fun `a busy encoder is waited for briefly, said, and then the remakes are left out`() = runBlocking {
        Locks.stage.lock()
        val seen = mutableListOf<Boolean>()
        val turn = StageTurn(waitMs = 50L, yieldMs = 60_000L, onWaiting = { seen += it }, clock = { 1_000L })
        val began = System.currentTimeMillis()
        assertFalse(turn.take())
        assertEquals("the screen is told while it waits, and when it stops", listOf(true, false), seen)
        assertTrue("the run is asked to give way while the batch waits", Locks.runShouldYield(0L))
        // The next row does not wait again: one bounded wait per batch.
        assertFalse(turn.take())
        assertTrue("one short wait in all", System.currentTimeMillis() - began < 2_000L)
        assertEquals("each left-out remake is counted, so it is named", 2, turn.refusals)
        turn.close()
        assertTrue("the encoder still belongs to its holder", Locks.stage.isLocked)
        assertEquals(0, Locks.freeUpWaiting.get())
        // The run starts no new file for as long as one can take, so a
        // second try finds the encoder free, and no longer than that.
        assertTrue(Locks.runShouldYield(1_000L + 59_999L))
        assertFalse(Locks.runShouldYield(1_000L + 60_000L))
        Locks.stage.unlock()
    }

    @Test
    fun `a file that finishes within the wait hands the encoder over`() = runBlocking {
        Locks.stage.lock()
        val waiting = CompletableDeferred<Unit>()
        val turn = StageTurn(waitMs = 5_000L, yieldMs = 60_000L, onWaiting = { if (it) waiting.complete(Unit) })
        val asked = async { turn.take() }
        waiting.await()
        delay(20L)
        Locks.stage.unlock()
        assertTrue(asked.await())
        assertTrue(Locks.stage.isLocked)
        turn.close()
        assertFalse(Locks.stage.isLocked)
    }

    @Test
    fun `a batch that leaves the screen mid-wait holds nothing up`() = runBlocking {
        Locks.stage.lock()
        val waiting = CompletableDeferred<Unit>()
        val turn = StageTurn(waitMs = 60_000L, yieldMs = 60_000L, onWaiting = { if (it) waiting.complete(Unit) })
        val asked = async { try { turn.take() } finally { turn.close() } }
        waiting.await()
        asked.cancel()
        asked.join()
        Locks.stage.unlock()
        assertFalse("the encoder is not left taken", Locks.stage.isLocked)
        assertFalse("nor the run held up", Locks.runShouldYield())
        assertEquals(0, Locks.freeUpWaiting.get())
    }

    @Test
    fun `a batch that got its turn ends the wait an earlier one left`() = runBlocking {
        Locks.runYieldsUntil.set(Long.MAX_VALUE - 1)
        val turn = StageTurn(waitMs = 1_000L, yieldMs = 60_000L)
        assertTrue(turn.take())
        turn.close()
        assertFalse(Locks.runShouldYield())
    }

    @Test
    fun `the wait a refused batch leaves is kept by a clock no one can set`() = runBlocking {
        // On the wall clock, a clock set back a day after a refusal held
        // every background run up for that day as well.
        var sinceBoot = 5_000L
        Locks.runClock = { sinceBoot }
        Locks.stage.lock()
        val turn = StageTurn(waitMs = 10L, yieldMs = 60_000L)
        assertFalse(turn.take())
        turn.close()
        Locks.stage.unlock()
        assertTrue("the run gives way for one file's time", Locks.runShouldYield())
        sinceBoot += 59_999L
        assertTrue(Locks.runShouldYield())
        sinceBoot += 1L
        assertFalse("and carries on after it, whatever the date says", Locks.runShouldYield())
        // The worker asks by the same clock, never by the date.
        val worker = File("src/main/kotlin/app/entesaver/work/CompressWorker.kt").readText()
        assertTrue(worker.contains("if (Locks.runShouldYield()) break@loop"))
        val locks = File("src/main/kotlin/app/entesaver/util/Locks.kt").readText()
        assertTrue(locks.contains("var runClock: () -> Long = { SystemClock.elapsedRealtime() }"))
        val stageTurn = File("src/main/kotlin/app/entesaver/util/StageTurn.kt").readText()
        assertFalse(stageTurn.contains("currentTimeMillis"))
    }
}
