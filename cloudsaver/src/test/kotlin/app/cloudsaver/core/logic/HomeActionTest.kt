package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionTest {

    private fun decide(
        queued: Int = 10,
        running: Boolean = false,
        paused: Boolean = false,
        thermalThrottled: Boolean = false,
        batteryPct: Int = 80,
        plugged: Boolean = false,
        freeBytes: Long = 20_000_000_000L,
        minFreeBytes: Long = 1_500_000_000L,
        waitReason: RunDecider.Wait = RunDecider.Wait.NONE
    ) = HomeAction.decide(
        queued, running, paused, thermalThrottled, batteryPct, plugged,
        freeBytes, minFreeBytes, waitReason
    )

    @Test
    fun `offered when there is something to do`() {
        val s = decide()
        assertEquals(HomeAction.Visibility.BUTTON, s.visibility)
        assertTrue(s.enabled)
        assertEquals(HomeAction.Note.JUST_STARTS_IT, s.note)
    }

    @Test
    fun `hidden when the queue is empty`() {
        // A button whose only possible outcome is "nothing happened".
        assertEquals(HomeAction.Visibility.HIDDEN, decide(queued = 0).visibility)
    }

    @Test
    fun `hidden while paused`() {
        assertEquals(HomeAction.Visibility.HIDDEN, decide(paused = true).visibility)
    }

    @Test
    fun `a run in progress shows progress, not an action`() {
        val s = decide(running = true)
        assertEquals(HomeAction.Visibility.WORKING, s.visibility)
        assertFalse(s.enabled)
    }

    @Test
    fun `it overrides what the scheduler is waiting for`() {
        for (reason in listOf(
            RunDecider.Wait.NOT_CHARGING,
            RunDecider.Wait.SCREEN_ON,
            RunDecider.Wait.BUDGET_USED,
            RunDecider.Wait.PHOTO_CAP,
            RunDecider.Wait.BATTERY_SAVER
        )) {
            val s = decide(waitReason = reason)
            assertTrue(reason.name, s.enabled)
            assertEquals(reason.name, HomeAction.Note.OVERRIDES_WAITING, s.note)
        }
    }

    @Test
    fun `it never overrides a safety guard`() {
        val hot = decide(thermalThrottled = true)
        assertEquals(HomeAction.Blocker.TOO_HOT, hot.blocker)
        assertFalse(hot.enabled)
        assertEquals(HomeAction.Visibility.BUTTON, hot.visibility)

        val flat = decide(batteryPct = 9)
        assertEquals(HomeAction.Blocker.BATTERY_LOW, flat.blocker)
        assertFalse(flat.enabled)

        val full = decide(freeBytes = 200_000_000L, minFreeBytes = 1_500_000_000L)
        assertEquals(HomeAction.Blocker.NOT_ENOUGH_SPACE, full.blocker)
        assertFalse(full.enabled)
    }

    @Test
    fun `a charger makes a low battery a non-issue`() {
        val s = decide(batteryPct = 5, plugged = true)
        assertEquals(HomeAction.Blocker.NONE, s.blocker)
        assertTrue(s.enabled)
    }

    @Test
    fun `an unknown battery level does not block`() {
        // Zero means "not read yet", not "flat". Blocking on it would leave
        // the button permanently disabled on a device that reports nothing.
        assertEquals(HomeAction.Blocker.NONE, decide(batteryPct = 0).blocker)
    }

    @Test
    fun `heat wins over everything else`() {
        val s = decide(thermalThrottled = true, batteryPct = 5, freeBytes = 1)
        assertEquals(HomeAction.Blocker.TOO_HOT, s.blocker)
    }

    @Test
    fun `the verify link appears only when the app cannot check for itself`() {
        assertTrue(
            HomeAction.showVerifyLink(usageAccessGranted = false, cloudRemovesItsUploads = true)
        )
        // Granted: the check is automatic, and a button implying otherwise
        // teaches people the automatic part does not work.
        assertFalse(
            HomeAction.showVerifyLink(usageAccessGranted = true, cloudRemovesItsUploads = true)
        )
        // Nothing to observe on a cloud that leaves its uploads in place.
        assertFalse(
            HomeAction.showVerifyLink(usageAccessGranted = false, cloudRemovesItsUploads = false)
        )
        assertFalse(
            HomeAction.showVerifyLink(usageAccessGranted = true, cloudRemovesItsUploads = false)
        )
    }
}
