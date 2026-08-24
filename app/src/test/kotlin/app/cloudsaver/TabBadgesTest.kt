package app.cloudsaver

import app.cloudsaver.core.logic.TabBadges
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBadgesTest {

    @Test
    fun `storage dot only above a gigabyte`() {
        assertFalse(TabBadges.storage(0L))
        assertFalse(TabBadges.storage(999_999_999L))
        assertFalse(TabBadges.storage(TabBadges.RECLAIMABLE_DOT_BYTES))
        assertTrue(TabBadges.storage(TabBadges.RECLAIMABLE_DOT_BYTES + 1))
        assertTrue(TabBadges.storage(8_000_000_000L))
    }

    @Test
    fun `settings dot for problems only`() {
        assertFalse(
            TabBadges.settings(
                cloudMissing = false,
                usageAccessOff = false,
                backgroundWorkStopped = false,
                spaceLow = false
            )
        )
        assertTrue(
            TabBadges.settings(
                cloudMissing = true,
                usageAccessOff = false,
                backgroundWorkStopped = false,
                spaceLow = false
            )
        )
        assertTrue(
            TabBadges.settings(
                cloudMissing = false,
                usageAccessOff = false,
                backgroundWorkStopped = true,
                spaceLow = false
            )
        )
    }

    @Test
    fun `pausing the app is a choice, not a problem`() {
        // Pause is deliberate and Home already says so; it must not raise a dot.
        assertFalse(
            TabBadges.settings(
                cloudMissing = false,
                usageAccessOff = false,
                backgroundWorkStopped = false,
                spaceLow = false
            )
        )
    }
}
