package app.cloudsaver.util

import app.cloudsaver.core.logic.PhotoFormat
import app.cloudsaver.core.logic.PhotoSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** A phone's decode ceiling only ever makes a photo smaller, never larger. */
class DeviceTierTest {

    private fun spec(mp: Int) = PhotoSpec(PhotoFormat.JPEG, mp, 82)

    @Test
    fun `Original is held to the ceiling`() {
        assertEquals(24, DeviceTier.capped(spec(0), 24).maxMp)
    }

    @Test
    fun `a setting under the ceiling is left alone`() {
        assertEquals(16, DeviceTier.capped(spec(16), 24).maxMp)
        assertEquals(12, DeviceTier.capped(spec(12), 16).maxMp)
    }

    @Test
    fun `a setting over the ceiling comes down to it`() {
        assertEquals(16, DeviceTier.capped(spec(24), 16).maxMp)
    }

    @Test
    fun `no ceiling changes nothing`() {
        assertEquals(spec(0), DeviceTier.capped(spec(0), 0))
        assertEquals(spec(24), DeviceTier.capped(spec(24), 0))
    }

    @Test
    fun `the steps only go down, and stop at 12`() {
        assertEquals(DeviceTier.STEPS_MP.sortedDescending(), DeviceTier.STEPS_MP)
        assertEquals(DeviceTier.LOW_END_CEILING_MP, DeviceTier.STEPS_MP.first())
        assertEquals(12, DeviceTier.STEPS_MP.last())
    }

    @Test
    fun `the phone's memory sets the ceiling, and every phone has one`() {
        val gb = 1_000_000_000L
        assertEquals(DeviceTier.Tier.VERY_LOW, DeviceTier.tierFor(2 * gb, lowRamDevice = false))
        assertEquals(DeviceTier.Tier.VERY_LOW, DeviceTier.tierFor(6 * gb, lowRamDevice = true))
        // A "4 GB" phone reports about 3.7 GB.
        assertEquals(DeviceTier.Tier.LOW, DeviceTier.tierFor(3_700_000_000L, lowRamDevice = false))
        assertEquals(DeviceTier.Tier.NORMAL, DeviceTier.tierFor(8 * gb, lowRamDevice = false))
        assertEquals(12, DeviceTier.baseCeilingMp(DeviceTier.Tier.VERY_LOW))
        assertEquals(24, DeviceTier.baseCeilingMp(DeviceTier.Tier.LOW))
        assertEquals(50, DeviceTier.baseCeilingMp(DeviceTier.Tier.NORMAL))
    }

    @Test
    fun `short of memory right now, the photo is made at 12 MP`() {
        val mb = 1_000_000L
        // Plenty free: the ceiling stands.
        assertEquals(24, DeviceTier.fitToMemory(24, availBytes = 1500 * mb, thresholdBytes = 200 * mb, lowMemory = false))
        // A 24 MP bitmap is 96 MB, and two of them must fit above Android's own line.
        assertEquals(24, DeviceTier.fitToMemory(24, availBytes = 400 * mb, thresholdBytes = 200 * mb, lowMemory = false))
        assertEquals(12, DeviceTier.fitToMemory(24, availBytes = 350 * mb, thresholdBytes = 200 * mb, lowMemory = false))
        assertEquals(12, DeviceTier.fitToMemory(50, availBytes = 4000 * mb, thresholdBytes = 200 * mb, lowMemory = true))
        // Never raised, and 12 MP is the floor.
        assertEquals(12, DeviceTier.fitToMemory(12, availBytes = 10 * mb, thresholdBytes = 200 * mb, lowMemory = true))
    }
}
