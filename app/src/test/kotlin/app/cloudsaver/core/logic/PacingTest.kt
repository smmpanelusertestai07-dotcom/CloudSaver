package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingTest {

    private val cap = 250L * 1_000_000 // 250 MB

    @Test
    fun `a slice is a twelfth of the day, rounded up`() {
        // Rounded up rather than down: twelve rounded-down slices would leave
        // a remainder that never goes out.
        val slice = Pacing.sliceBytes(cap)
        assertTrue(slice * Pacing.SLICES_PER_DAY >= cap)
        assertTrue((slice - 1) * Pacing.SLICES_PER_DAY < cap)
    }

    @Test
    fun `unlimited stays unlimited all the way through`() {
        assertEquals(-1L, Pacing.sliceBytes(-1))
        assertEquals(-1L, Pacing.remainingToday(-1, 999))
        assertEquals(-1L, Pacing.allowanceNow(-1, 999))
        assertEquals(-1L, Pacing.dailyBudgetWithCatchUp(-1, 500))
    }

    @Test
    fun `a slice is never zero, so one file can always move`() {
        // A cap smaller than the slice count must not round down to nothing,
        // or the pipeline would stall for good.
        assertTrue(Pacing.sliceBytes(5) >= 1)
    }

    @Test
    fun `allowance is capped by what is left of the day`() {
        val nearlySpent = cap - 1_000
        assertEquals(1_000L, Pacing.allowanceNow(cap, nearlySpent))
        assertEquals(0L, Pacing.allowanceNow(cap, cap))
        assertEquals(0L, Pacing.allowanceNow(cap, cap * 2))
    }

    @Test
    fun `without an oracle exactly one copy may be in flight`() {
        assertEquals(1, Pacing.inFlightLimit(cloudHasFreeUpOracle = false))
        assertEquals(4, Pacing.inFlightLimit(cloudHasFreeUpOracle = true))
    }

    @Test
    fun `a waiting copy holds the only slot`() {
        val now = 10_000_000L
        val justReleased = listOf(now - 60_000)
        assertEquals(0, Pacing.slotsFree(justReleased, now, cloudHasFreeUpOracle = false))
        assertEquals(3, Pacing.slotsFree(justReleased, now, cloudHasFreeUpOracle = true))
    }

    @Test
    fun `a timed-out copy stops blocking the queue`() {
        val now = 100_000_000L
        val stale = listOf(now - Pacing.IN_FLIGHT_TIMEOUT_MS - 1)
        assertTrue(Pacing.isTimedOut(stale.first(), now))
        assertEquals(1, Pacing.slotsFree(stale, now, cloudHasFreeUpOracle = false))
    }

    @Test
    fun `an unused day carries forward, but only one`() {
        assertEquals(cap, Pacing.carryForward(cap, releasedYesterday = 0))
        assertEquals(cap / 2, Pacing.carryForward(cap, releasedYesterday = cap / 2))
        assertEquals(0L, Pacing.carryForward(cap, releasedYesterday = cap))
        // Yesterday cannot leave more than a full day behind.
        assertTrue(Pacing.carryForward(cap, releasedYesterday = -1) <= cap)
    }

    @Test
    fun `catch-up never lets a week of uploads land in one afternoon`() {
        val carried = cap * 7
        assertEquals(cap * 2, Pacing.dailyBudgetWithCatchUp(cap, carried))
    }

    @Test
    fun `a backlog is not paced, because throughput matters more`() {
        // One copy at a time on a ten-year gallery would never finish; those
        // go out at slice speed and settle for batch evidence.
        assertEquals(
            null,
            Pacing.releaseSlots(
                slotsFree = 0,
                stagedWaiting = Pacing.BACKLOG_BURST_ITEMS + 1,
                perFileProofPossible = true
            )
        )
    }

    @Test
    fun `a short queue is paced, so each file can be proved`() {
        assertEquals(
            1,
            Pacing.releaseSlots(slotsFree = 1, stagedWaiting = 3, perFileProofPossible = true)
        )
        assertEquals(
            0,
            Pacing.releaseSlots(slotsFree = 0, stagedWaiting = 3, perFileProofPossible = true)
        )
    }

    @Test
    fun `without a way to measure, pacing buys nothing and is skipped`() {
        assertEquals(
            null,
            Pacing.releaseSlots(slotsFree = 0, stagedWaiting = 1, perFileProofPossible = false)
        )
    }

    @Test
    fun `a spent day gives no allowance even with catch-up`() {
        val budget = Pacing.dailyBudgetWithCatchUp(cap, cap)
        assertEquals(0L, Pacing.allowanceNow(budget, budget))
        assertFalse(Pacing.allowanceNow(budget, budget / 2) == 0L)
    }
}
