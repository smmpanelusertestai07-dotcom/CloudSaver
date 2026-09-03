package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run Android cut short must not read as a run that finished.
 *
 * Nothing read `getStopReason()`, so both stamped the last-run time and both
 * left every health check green - and the app said it was well while
 * completing a fraction of the work. These are the numbers the platform
 * actually reports, and the split between "the phone is rationing us", which
 * the user can do something about, and "the app or a constraint ended it",
 * which Home already explains in its own words.
 */
class StopsTest {

    @Test
    fun `the reasons that matter are named, not printed as integers`() {
        assertEquals("QUOTA", Stops.name(10))
        assertEquals("APP_STANDBY", Stops.name(12))
        assertEquals("FOREGROUND_SERVICE_TIMEOUT", Stops.name(16))
        assertEquals("CONSTRAINT_CHARGING", Stops.name(6))
        assertEquals("CANCELLED_BY_APP", Stops.name(1))
        assertEquals("NOT_STOPPED", Stops.name(-256))
    }

    @Test
    fun `an unknown or future reason still has a word`() {
        // A number this build has never heard of must not surface as "99" or
        // crash a log line; the app has one string for "Android stopped us".
        assertEquals(Stops.UNKNOWN, Stops.name(99))
        assertEquals(Stops.UNKNOWN, Stops.name(-1))
        assertEquals(Stops.UNKNOWN, Stops.name(0))
    }

    @Test
    fun `being rationed is not the same as being idle`() {
        // These mean the work started and was taken away. The remedy is a
        // setting only the user can reach, so the app has to say so.
        for (r in listOf(
            "QUOTA", "APP_STANDBY", "BACKGROUND_RESTRICTION",
            "TIMEOUT", "FOREGROUND_SERVICE_TIMEOUT", "DEVICE_STATE", "PREEMPT"
        )) {
            assertTrue("$r means the phone is rationing us", Stops.isRationed(r))
        }
        // These already have their own sentence on Home, and treating them as
        // a fault would put a warning chip over a perfectly normal wait.
        for (r in listOf(
            "", "NOT_STOPPED", "CANCELLED_BY_APP", "USER",
            "CONSTRAINT_CHARGING", "CONSTRAINT_BATTERY_NOT_LOW",
            "CONSTRAINT_DEVICE_IDLE", "CONSTRAINT_STORAGE_NOT_LOW",
            "CONSTRAINT_CONNECTIVITY", Stops.UNKNOWN
        )) {
            assertFalse("$r must not raise the stalled warning", Stops.isRationed(r))
        }
    }

    @Test
    fun `a finished run reports no reason at all`() {
        // The empty string is what the worker writes when it ended on its own
        // terms, and it must never look like a fault.
        assertFalse(Stops.isRationed(""))
    }
}
