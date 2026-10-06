package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCapabilityTest {

    @Test
    fun `Ente frees up space and checks for duplicates`() {
        assertTrue(CloudCapability.ENTE.hasFreeUpSpace)
        assertTrue(CloudCapability.ENTE.hasHashDedupe)
    }

    @Test
    fun `only a cloud that frees up space can be believed when a copy vanishes`() {
        assertTrue(CloudCapability.hasDisappearanceOracle(CloudCapability.ENTE))
        assertFalse(
            CloudCapability.hasDisappearanceOracle(
                CloudCapability.Caps(hasFreeUpSpace = false, hasHashDedupe = false)
            )
        )
    }

    @Test
    fun `a cloud without duplicate checks gets a day of patience`() {
        // A cloud that stores a re-sent file twice must not have a slow upload
        // mistaken for a lost one. Ente collapses the re-send, so no wait.
        assertEquals(
            24 * 3_600_000L,
            CloudCapability.resendQuietPeriodMs(
                CloudCapability.Caps(hasFreeUpSpace = true, hasHashDedupe = false)
            )
        )
        assertEquals(0L, CloudCapability.resendQuietPeriodMs(CloudCapability.ENTE))
    }
}
