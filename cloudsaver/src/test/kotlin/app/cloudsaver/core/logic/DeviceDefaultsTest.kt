package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDefaultsTest {

    private val gb = 1024L * Defaults.MB

    @Test
    fun `a small phone keeps the floor reserve, a big one keeps more`() {
        // 5% of 16 GB is under the floor, so the floor wins.
        assertEquals(1536, DeviceDefaults.reserveMb(16 * gb))
        // 5% of 512 GB is far more than the floor.
        assertTrue(DeviceDefaults.reserveMb(512 * gb) > 1536)
    }

    @Test
    fun `the app never claims more than five gigabytes of the phone`() {
        assertEquals(5 * 1024, DeviceDefaults.ownLimitMb(freeBytes = 400 * gb, dailyCapMb = 250))
    }

    @Test
    fun `the app always gets room for two days of releases`() {
        // A nearly full phone would otherwise be allotted less than one day's
        // output, and the pipeline would jam against its own limit.
        val limit = DeviceDefaults.ownLimitMb(freeBytes = 1 * gb, dailyCapMb = 1024)
        assertTrue(limit >= 2048)
    }

    @Test
    fun `small or nearly full phones get the cautious cap`() {
        assertEquals(250, DeviceDefaults.dailyCapMb(64 * gb, 40 * gb, wifiShareLast7Days = 1.0))
        assertEquals(250, DeviceDefaults.dailyCapMb(256 * gb, 2 * gb, wifiShareLast7Days = 1.0))
    }

    @Test
    fun `a phone that lives on wifi can afford more`() {
        assertEquals(1024, DeviceDefaults.dailyCapMb(256 * gb, 100 * gb, wifiShareLast7Days = 0.9))
        assertEquals(500, DeviceDefaults.dailyCapMb(256 * gb, 100 * gb, wifiShareLast7Days = 0.2))
    }

    @Test
    fun `a hint appears only when the stored value is far out`() {
        assertFalse(DeviceDefaults.looksWrong(current = 500, recommended = 250))
        assertTrue(DeviceDefaults.looksWrong(current = 2048, recommended = 250))
        assertTrue(DeviceDefaults.looksWrong(current = 250, recommended = 2048))
        // Unlimited (-1) is a deliberate choice, not a drifted value.
        assertFalse(DeviceDefaults.looksWrong(current = -1, recommended = 250))
    }

    @Test
    fun `Automatic lands every limit on a step Settings offers`() {
        for ((total, free) in listOf(16 * gb to 1 * gb, 64 * gb to 20 * gb, 256 * gb to 150 * gb, Long.MAX_VALUE to Long.MAX_VALUE)) {
            val l = DeviceDefaults.automatic(total, free)
            assertTrue("$l", l.dailyCapMb in Defaults.DAILY_CAP_CHOICES_MB && l.dailyCapMb > 0)
            assertTrue("$l", l.minFreeMb in Defaults.MIN_FREE_CHOICES_MB)
            assertTrue("$l", l.maxExtraMb in Defaults.MAX_EXTRA_CHOICES_MB && l.maxExtraMb > 0)
        }
        // A small or nearly full phone gets the cautious daily amount.
        assertEquals(250, DeviceDefaults.automatic(64 * gb, 2 * gb).dailyCapMb)
    }

    @Test
    fun `Automatic never asks a nearly full phone to keep more free than it has`() {
        // 128 GB with 4 GB free: a twentieth would be 6.4 GB, more than is free.
        assertEquals(1500, DeviceDefaults.automatic(128 * gb, 4 * gb).minFreeMb)
        // With room to spare, a big phone keeps more.
        assertEquals(5000, DeviceDefaults.automatic(256 * gb, 100 * gb).minFreeMb)
        // A step is never rounded up past the figure worked out.
        assertEquals(1500, DeviceDefaults.floorChoice(2900, Defaults.MIN_FREE_CHOICES_MB))
        assertEquals(1500, DeviceDefaults.floorChoice(100, Defaults.MIN_FREE_CHOICES_MB))
    }
}
