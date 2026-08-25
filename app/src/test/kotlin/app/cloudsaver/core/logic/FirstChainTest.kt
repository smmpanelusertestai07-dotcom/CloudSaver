package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Z10.6: one success card when the first file is confirmed, one stalled card
 * after 48 silent hours, and dismissal is final either way.
 */
class FirstChainTest {

    private val h48 = FirstChain.STALL_MS

    @Test
    fun `nothing shows before anything was released`() {
        assertEquals("", FirstChain.next("", firstReleaseAt = 0, confirmedCount = 0, now = 1))
    }

    @Test
    fun `the first confirmation flips to success`() {
        assertEquals(
            "SUCCESS",
            FirstChain.next("", firstReleaseAt = 100, confirmedCount = 1, now = 200)
        )
    }

    @Test
    fun `48 silent hours after the first release flips to stalled`() {
        assertEquals(
            "",
            FirstChain.next("", firstReleaseAt = 1_000, confirmedCount = 0, now = 1_000 + h48 - 1)
        )
        assertEquals(
            "STALLED",
            FirstChain.next("", firstReleaseAt = 1_000, confirmedCount = 0, now = 1_000 + h48)
        )
    }

    @Test
    fun `a late confirmation replaces the stalled card with the success one`() {
        // The 48-hour card names two likely causes; the user fixes one; the
        // confirmation that follows deserves the success card, not silence.
        assertEquals(
            "SUCCESS",
            FirstChain.next("STALLED", firstReleaseAt = 1_000, confirmedCount = 1, now = 1)
        )
    }

    @Test
    fun `dismissed stays dismissed, whatever happens after`() {
        assertEquals(
            "DONE",
            FirstChain.next("DONE", firstReleaseAt = 1_000, confirmedCount = 5, now = 1)
        )
        assertEquals(
            "DONE",
            FirstChain.next("DONE", firstReleaseAt = 1_000, confirmedCount = 0, now = h48 * 2)
        )
    }

    @Test
    fun `a pending success card does not re-trigger itself`() {
        assertEquals(
            "SUCCESS",
            FirstChain.next("SUCCESS", firstReleaseAt = 1_000, confirmedCount = 3, now = 1)
        )
    }
}
