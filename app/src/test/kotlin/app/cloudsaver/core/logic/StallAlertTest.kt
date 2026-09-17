package app.cloudsaver.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone has stopped the background work: one notification, a week
 * apart, three in all. The same evidence Home's chip uses, decided once.
 */
class StallAlertTest {

    private val day = 86_400_000L
    private val now = 100 * day

    @Test
    fun `stalled means work waited while nothing ran for two days, or runs were rationed`() {
        assertTrue(StallAlert.stalled(now, lastRunAt = now - 3 * day, waiting = 5, rationed = false))
        assertTrue(StallAlert.stalled(now, lastRunAt = now - 1 * day, waiting = 5, rationed = true))
        // A run yesterday, nothing rationed: the phone is doing its job.
        assertFalse(StallAlert.stalled(now, lastRunAt = now - 1 * day, waiting = 5, rationed = false))
        // Nothing waiting: silence is not a stall.
        assertFalse(StallAlert.stalled(now, lastRunAt = now - 10 * day, waiting = 0, rationed = false))
        // Never run at all is setup, not a stall.
        assertFalse(StallAlert.stalled(now, lastRunAt = 0, waiting = 5, rationed = false))
    }

    @Test
    fun `the first alert goes at once and the next waits a week`() {
        assertTrue(StallAlert.due(stalled = true, sentCount = 0, lastSentAt = 0, now = now))
        assertFalse(StallAlert.due(stalled = true, sentCount = 1, lastSentAt = now - 6 * day, now = now))
        assertTrue(StallAlert.due(stalled = true, sentCount = 1, lastSentAt = now - 7 * day, now = now))
    }

    @Test
    fun `three alerts in all, then the chip on Home is the only notice`() {
        assertTrue(StallAlert.due(stalled = true, sentCount = 2, lastSentAt = now - 30 * day, now = now))
        assertFalse(StallAlert.due(stalled = true, sentCount = 3, lastSentAt = now - 30 * day, now = now))
        assertFalse(StallAlert.due(stalled = true, sentCount = 99, lastSentAt = 0, now = now))
    }

    @Test
    fun `nothing is owed when the work is not stalled`() {
        assertFalse(StallAlert.due(stalled = false, sentCount = 0, lastSentAt = 0, now = now))
    }

    @Test
    fun `the cadence is a week and three, and the stall window is two days`() {
        // Written down here because the Permissions screen and Help say so
        // in words; a number changed in one place must change the words.
        assertTrue(StallAlert.SPACING_MS == 7 * day)
        assertTrue(StallAlert.MAX_ALERTS == 3)
        assertTrue(StallAlert.STALL_MS == 2 * day)
    }
}
