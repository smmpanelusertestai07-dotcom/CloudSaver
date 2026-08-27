package app.cloudsaver

import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.DeviceDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the recommendations do when the phone cannot say how big it is.
 *
 * Storage.freeBytes answers Long.MAX_VALUE when StatFs throws, which happens
 * on a volume that has been unmounted, on a path the app has just lost
 * permission to, and on more than one manufacturer's SD card slot. That was a
 * deliberate choice: a figure the app cannot read must not stop the pipeline,
 * so "unknown" is expressed as "no shortage of it". Everything downstream is
 * then obliged to cope with a number that large.
 *
 * The two functions here did not. Both divided the figure down and then called
 * .toInt() on it, and a twentieth of Long.MAX_VALUE is still four hundred
 * times what an Int can hold, so the conversion wrapped round to whatever the
 * bottom thirty-two bits happened to be. The results were nonsense in the most
 * expensive direction: the phone reporting the most space was handed the
 * smallest reserve, and the phone with the most room to spare was told it
 * could only keep two days of output - the exact opposite of what each
 * function is for, and silent, because a plausible-looking number came back
 * either way.
 *
 * The clamp now happens while the value is still a Long. These are the
 * boundaries that were wrong, pinned so they cannot go quiet again.
 */
class UnmeasurableVolumeTest {

    private val gb = 1024L * Defaults.MB

    @Test
    fun `a volume that cannot be measured still gets a sensible reserve`() {
        // Not a wrapped-round number, and not the floor either: an unreadable
        // volume reads as an enormous one, so it is handed the largest
        // reserve the answer can hold rather than the smallest.
        assertEquals(Int.MAX_VALUE, DeviceDefaults.reserveMb(Long.MAX_VALUE))
    }

    @Test
    fun `a bigger phone is never asked to reserve less than a smaller one`() {
        // This is the whole promise of the function, and it was the promise
        // the overflow broke. Checked across the range rather than at one
        // point, because the failure was at one end only.
        val totals = listOf(
            0L, 8 * gb, 16 * gb, 64 * gb, 128 * gb, 512 * gb,
            2048 * gb, Long.MAX_VALUE / 2, Long.MAX_VALUE
        )
        val reserves = totals.map { DeviceDefaults.reserveMb(it) }
        assertEquals(reserves.sorted(), reserves)
        assertTrue(
            "no phone reserves less than the 1.5 GB floor: $reserves",
            reserves.all { it >= 1536 }
        )
    }

    @Test
    fun `a volume that cannot be measured gets the full five gigabyte ceiling`() {
        // Not the two-day floor. Plenty of space is the case the ceiling
        // exists for, and an unreadable volume is the case that used to be
        // handed the floor instead.
        assertEquals(5 * 1024, DeviceDefaults.ownLimitMb(Long.MAX_VALUE, dailyCapMb = 250))
        assertEquals(5 * 1024, DeviceDefaults.ownLimitMb(Long.MAX_VALUE, dailyCapMb = 0))
    }

    @Test
    fun `the app never asks for more room than the ceiling or less than two days`() {
        val frees = listOf(0L, 1 * gb, 16 * gb, 400 * gb, 4096 * gb, Long.MAX_VALUE)
        for (free in frees) {
            val limit = DeviceDefaults.ownLimitMb(free, dailyCapMb = 250)
            assertTrue("$free was allowed $limit MB, past the ceiling", limit <= 5 * 1024)
            assertTrue("$free was allowed $limit MB, under two days", limit >= 500)
        }
    }
}
