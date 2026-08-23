package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `our own deletion is never mistaken for an upload`() {
        val verdict = EvidenceRules.onCopyMissing(
            appDeletedIt = true, txSinceRelease = 0, fileBytes = 1000, resendCount = 0
        )
        assertEquals(EvidenceRules.MissingVerdict.WE_DELETED_IT, verdict)
    }

    @Test
    fun `gone plus matching traffic is proof of upload`() {
        val verdict = EvidenceRules.onCopyMissing(
            appDeletedIt = false, txSinceRelease = 1000, fileBytes = 1000, resendCount = 0
        )
        assertEquals(EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD, verdict)
    }

    @Test
    fun `gone with no traffic is worth another try`() {
        val verdict = EvidenceRules.onCopyMissing(
            appDeletedIt = false, txSinceRelease = 0, fileBytes = 1000, resendCount = 0
        )
        assertEquals(EvidenceRules.MissingVerdict.RESEND, verdict)
    }

    @Test
    fun `the app gives up rather than looping forever`() {
        val verdict = EvidenceRules.onCopyMissing(
            appDeletedIt = false,
            txSinceRelease = 0,
            fileBytes = 1000,
            resendCount = EvidenceRules.MAX_RESENDS
        )
        assertEquals(EvidenceRules.MissingVerdict.GIVE_UP, verdict)
    }

    @Test
    fun `proof beats the resend limit`() {
        // Having tried twice must not stop the app recognising success.
        val verdict = EvidenceRules.onCopyMissing(
            appDeletedIt = false,
            txSinceRelease = 1000,
            fileBytes = 1000,
            resendCount = 9
        )
        assertEquals(EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD, verdict)
    }

    // ---- what may be deleted ------------------------------------------------

    @Test
    fun `only observed proof reclaims an original immediately`() {
        assertTrue(EvidenceRules.mayReclaimOriginal(Evidence.CONFIRMED_EXACT, copyAgeDays = 0))
        assertFalse(EvidenceRules.mayReclaimOriginal(Evidence.CONFIRMED_PACED, copyAgeDays = 0))
        assertTrue(
            EvidenceRules.mayReclaimOriginal(
                Evidence.CONFIRMED_PACED, EvidenceRules.RECLAIM_MIN_DAYS
            )
        )
    }

    @Test
    fun `a batch total never reclaims an original on its own`() {
        assertFalse(EvidenceRules.mayReclaimOriginal(Evidence.VERIFIED, copyAgeDays = 3650))
        assertFalse(EvidenceRules.mayReclaimOriginal(Evidence.AGED, copyAgeDays = 3650))
        assertFalse(EvidenceRules.mayReclaimOriginal(Evidence.NONE, copyAgeDays = 3650))
    }

    @Test
    fun `deleting our own copy is a lower bar than deleting an original`() {
        assertTrue(EvidenceRules.mayDeleteCopy(Evidence.VERIFIED, Defaults.KEEP_MIN_DAYS))
        assertFalse(EvidenceRules.mayDeleteCopy(Evidence.VERIFIED, Defaults.KEEP_MIN_DAYS - 1))
        assertTrue(EvidenceRules.mayDeleteCopy(Evidence.AGED, Defaults.AGED_DAYS))
        assertFalse(EvidenceRules.mayDeleteCopy(Evidence.AGED, Defaults.AGED_DAYS - 1))
        assertFalse(EvidenceRules.mayDeleteCopy(Evidence.NONE, 10_000))
    }
}
