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
}
