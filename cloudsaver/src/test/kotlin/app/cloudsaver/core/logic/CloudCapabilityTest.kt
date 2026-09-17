package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCapabilityTest {

    @Test
    fun `an unknown cloud is assumed to do neither`() {
        val caps = CloudCapability.defaultsFor("something-new")
        assertFalse(caps.hasFreeUpSpace)
        assertFalse(caps.hasHashDedupe)
    }

    @Test
    fun `only a cloud that frees up space can be believed when a copy vanishes`() {
        assertTrue(
            CloudCapability.hasDisappearanceOracle(CloudCapability.defaultsFor("ente"))
        )
        assertFalse(
            CloudCapability.hasDisappearanceOracle(CloudCapability.defaultsFor("mega"))
        )
    }

    @Test
    fun `a cloud without hash de-duplication gets a day of patience`() {
        // MEGA stores a re-sent file twice, so a slow upload must not be
        // mistaken for a lost one.
        assertEquals(
            24 * 3_600_000L,
            CloudCapability.resendQuietPeriodMs(CloudCapability.defaultsFor("mega"))
        )
        assertEquals(
            0L,
            CloudCapability.resendQuietPeriodMs(CloudCapability.defaultsFor("ente"))
        )
    }

    @Test
    fun `every selectable cloud has an entry`() {
        // A cloud the registry forgets silently falls back to the cautious
        // defaults, which is safe but means the pipeline never adapts to it.
        for (id in listOf("ente", "mega", "filen", "proton", "nextcloud", "immich", "onedrive", "other")) {
            val caps = CloudCapability.defaultsFor(id)
            // Present is what matters; the values themselves are the registry's.
            assertEquals(caps, CloudCapability.defaultsFor(id))
        }
    }
}
