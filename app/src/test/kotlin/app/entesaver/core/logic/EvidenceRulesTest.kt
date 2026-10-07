package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceRulesTest {

    // ---- batch and exact ----------------------------------------------------

    @Test
    fun `nine tenths of a batch is enough`() {
        assertTrue(EvidenceRules.batchVerified(txBytes = 90, batchBytes = 100))
        assertFalse(EvidenceRules.batchVerified(txBytes = 89, batchBytes = 100))
    }

    @Test
    fun `an empty batch verifies nothing`() {
        assertFalse(EvidenceRules.batchVerified(txBytes = 10_000, batchBytes = 0))
    }

    @Test
    fun `a vanished copy needs its bytes to have gone out`() {
        assertTrue(EvidenceRules.confirmedExact(txSinceRelease = 900, fileBytes = 1000))
        assertFalse(EvidenceRules.confirmedExact(txSinceRelease = 100, fileBytes = 1000))
    }

    // ---- paced --------------------------------------------------------------

    @Test
    fun `paced proof needs the traffic to match the file both ways`() {
        assertTrue(EvidenceRules.confirmedPaced(txSinceRelease = 1000, fileBytes = 1000))
        assertTrue(EvidenceRules.confirmedPaced(txSinceRelease = 870, fileBytes = 1000))
        // Too little: it did not finish.
        assertFalse(EvidenceRules.confirmedPaced(txSinceRelease = 500, fileBytes = 1000))
        // Far too much: something else was uploading, so this proves nothing
        // about our file.
        assertFalse(EvidenceRules.confirmedPaced(txSinceRelease = 5000, fileBytes = 1000))
    }

    @Test
    fun `paced proof refuses nonsense inputs`() {
        assertFalse(EvidenceRules.confirmedPaced(txSinceRelease = 100, fileBytes = 0))
        assertFalse(EvidenceRules.confirmedPaced(txSinceRelease = -1, fileBytes = 100))
    }

    // ---- what a disappearance means ----------------------------------------

    private fun missing(attribution: EvidenceRules.Attribution, resendCount: Int = 0, ours: Boolean = false) =
        EvidenceRules.onCopyMissing(appDeletedIt = ours, attribution = attribution, resendCount = resendCount)

    @Test
    fun `our own deletion is never mistaken for an upload`() {
        assertEquals(
            EvidenceRules.MissingVerdict.WE_DELETED_IT,
            missing(EvidenceRules.Attribution.PER_FILE, ours = true)
        )
    }

    @Test
    fun `gone plus traffic that is its own is proof of upload`() {
        assertEquals(EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD, missing(EvidenceRules.Attribution.PER_FILE))
    }

    @Test
    fun `gone plus traffic that could be anything is the batch grade at most`() {
        assertEquals(EvidenceRules.MissingVerdict.BYTES_SENT, missing(EvidenceRules.Attribution.BYTES_SENT))
    }

    @Test
    fun `gone with no traffic is worth another try`() {
        assertEquals(EvidenceRules.MissingVerdict.RESEND, missing(EvidenceRules.Attribution.UNPROVEN))
    }

    @Test
    fun `the app gives up rather than looping forever`() {
        assertEquals(
            EvidenceRules.MissingVerdict.GIVE_UP,
            missing(EvidenceRules.Attribution.UNPROVEN, resendCount = EvidenceRules.MAX_RESENDS)
        )
    }

    @Test
    fun `proof beats the resend limit`() {
        // Having tried twice must not stop the app recognising success.
        assertEquals(
            EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD,
            missing(EvidenceRules.Attribution.PER_FILE, resendCount = 9)
        )
    }

    // ---- whose bytes they were ----------------------------------------------

    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun copy(id: Long, bytes: Long = 3_000_000, gone: Boolean = false, hoursAgo: Long = 1) =
        EvidenceRules.Waiting(id = id, releasedAt = now - hoursAgo * hour + id, bytes = bytes, gone = gone)

    @Test
    fun `camera uploads never make a vanished copy exact`() {
        // The finding: a 3 MB copy waits next to another, Ente uploads 3 MB of
        // camera photos, the person deletes the copy in the gallery. The bytes
        // cover it, but nothing says they were this file.
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true), copy(2)),
            txSinceEarliest = 3_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `one burst pays for one copy, not every copy that vanished`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true), copy(2, gone = true), copy(3, gone = true)),
            txSinceEarliest = 3_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[2L])
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[3L])
    }

    @Test
    fun `older copies still in the folder are paid first`() {
        // Ente sent one copy's worth: that was most likely the oldest one,
        // still sitting there - not the newer copy that disappeared.
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, hoursAgo = 3), copy(2, gone = true)),
            txSinceEarliest = 3_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[2L])
    }

    @Test
    fun `a copy alone in flight whose size matches is its own proof`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true)),
            txSinceEarliest = 3_100_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.PER_FILE, result[1L])
    }

    @Test
    fun `far more traffic than the file proves nothing about it`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true)),
            txSinceEarliest = 30_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `a copy alone but past its window is not exact`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true, hoursAgo = 30)),
            txSinceEarliest = 3_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `unreadable traffic proves nothing`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, gone = true)),
            txSinceEarliest = null,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[1L])
    }

    @Test
    fun `a copy of unknown size is never covered`() {
        val result = EvidenceRules.attributeTraffic(
            listOf(copy(1, bytes = 0, gone = true)),
            txSinceEarliest = 3_000_000,
            now = now
        )
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[1L])
    }

    // ---- paced proof: alone means alone -------------------------------------

    @Test
    fun `a timed-out copy still waiting means nothing is alone`() {
        // Copy 1 went out on mobile data and timed out; Ente (Wi-Fi only)
        // still has it to send. Copy 2 released "alone" beside it must not
        // be credited with copy 1's bytes.
        val waiting = listOf(copy(1, hoursAgo = 8), copy(2))
        assertNull(EvidenceRules.aloneInFlight(waiting, now))
    }

    @Test
    fun `a single copy inside its window is alone`() {
        assertEquals(1L, EvidenceRules.aloneInFlight(listOf(copy(1)), now)?.id)
    }

    @Test
    fun `a single copy past its window is not judged`() {
        assertNull(EvidenceRules.aloneInFlight(listOf(copy(1, hoursAgo = 8)), now))
    }

    // ---- the return from Ente's free-up screen ------------------------------

    private val tapAt = now - 10 * 60_000L
    private val window = EvidenceRules.ConfirmWindow(openedAt = tapAt, presentAtTap = setOf(1L))

    private fun collected(
        window: EvidenceRules.ConfirmWindow? = this.window,
        id: Long = 1,
        at: Long = now,
        evidence: Evidence = Evidence.NONE,
        ours: Boolean = false,
        tx: Long? = null
    ) = EvidenceRules.collectedByFreeUp(window, id, at, evidence, ours, tx, fileBytes = 1000)

    @Test
    fun `a copy there at the tap and gone on the return was collected`() {
        assertTrue(collected())
        // A copy that had only aged has no proof to protect.
        assertTrue(collected(evidence = Evidence.AGED))
    }

    @Test
    fun `a copy already missing at the tap was not collected by Ente`() {
        assertFalse(collected(id = 2))
    }

    @Test
    fun `no window, no shortcut`() {
        // Every pass but the return - the hourly worker, the folder
        // observer - judges by the normal rules.
        assertFalse(collected(window = null))
    }

    @Test
    fun `the return is believed only soon after the tap`() {
        assertTrue(collected(at = tapAt + Defaults.CONFIRM_WINDOW_MS))
        assertFalse(collected(at = tapAt + Defaults.CONFIRM_WINDOW_MS + 1))
        assertFalse(collected(at = tapAt + 24 * hour))
        assertFalse(collected(at = tapAt - 1))
    }

    @Test
    fun `the shortcut never overrides our own deletion or real proof`() {
        assertFalse(collected(ours = true))
        assertFalse(collected(evidence = Evidence.VERIFIED))
        assertFalse(collected(evidence = Evidence.CONFIRMED_PACED))
    }

    @Test
    fun `readable traffic must also cover the copy`() {
        assertFalse(collected(tx = 0))
        assertTrue(collected(tx = 1000))
    }
}
