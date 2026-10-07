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
        val result = attribute(
            listOf(copy(1, gone = true), copy(2)),
            tx = 3_000_000
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `one burst pays for one copy, not every copy that vanished`() {
        val result = attribute(
            listOf(copy(1, gone = true), copy(2, gone = true), copy(3, gone = true)),
            tx = 3_000_000
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[2L])
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[3L])
    }

    @Test
    fun `older copies still in the folder are paid first`() {
        // Ente sent one copy's worth: that was most likely the oldest one,
        // still sitting there - not the newer copy that disappeared.
        val result = attribute(
            listOf(copy(1, hoursAgo = 3), copy(2, gone = true)),
            tx = 3_000_000
        )
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[2L])
    }

    @Test
    fun `a copy alone in flight whose size matches is its own proof`() {
        val result = attribute(
            listOf(copy(1, gone = true)),
            tx = 3_100_000
        )
        assertEquals(EvidenceRules.Attribution.PER_FILE, result[1L])
    }

    @Test
    fun `far more traffic than the file proves nothing about it`() {
        val result = attribute(
            listOf(copy(1, gone = true)),
            tx = 30_000_000
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `a copy alone but past its window is not exact`() {
        val result = attribute(
            listOf(copy(1, gone = true, hoursAgo = 30)),
            tx = 3_000_000
        )
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[1L])
    }

    @Test
    fun `unreadable traffic proves nothing`() {
        val result = attribute(
            listOf(copy(1, gone = true)),
            tx = null
        )
        assertEquals(EvidenceRules.Attribution.UNPROVEN, result[1L])
    }

    @Test
    fun `a copy of unknown size is never covered`() {
        val result = attribute(
            listOf(copy(1, bytes = 0, gone = true)),
            tx = 3_000_000
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
        assertNull(alone(waiting, tx = 3_000_000))
    }

    @Test
    fun `a single copy inside its window is alone`() {
        assertEquals(1L, alone(listOf(copy(1)), tx = 3_000_000)?.id)
    }

    @Test
    fun `a single copy past its window is not judged`() {
        assertNull(alone(listOf(copy(1, hoursAgo = 8)), tx = 3_000_000))
    }

    @Test
    fun `a single copy whose size does not match is not judged`() {
        assertNull(alone(listOf(copy(1)), tx = 30_000_000))
        assertNull(alone(listOf(copy(1)), tx = 1_000_000))
    }

    // ---- any other copy in the window leaves nothing alone ------------------

    private fun graded(id: Long, hoursAgo: Long = 12 * 24, gone: Boolean = false) =
        copy(id, gone = gone, hoursAgo = hoursAgo).copy(graded = true)

    private fun verified(id: Long, bytes: Long, verifiedHoursAgo: Long = 30 * 24, releasedHoursAgo: Long = 40 * 24) =
        graded(id, hoursAgo = releasedHoursAgo).copy(bytes = bytes, verifiedAt = now - verifiedHoursAgo * hour)

    private fun alone(
        waiting: List<EvidenceRules.Waiting>,
        tx: Long,
        left: List<EvidenceRules.Left> = emptyList()
    ) = EvidenceRules.aloneInFlight(waiting, left, now, tx)

    private fun attribute(
        waiting: List<EvidenceRules.Waiting>,
        tx: Long?,
        left: List<EvidenceRules.Left> = emptyList()
    ) = EvidenceRules.attributeTraffic(waiting, left, tx, now)

    /** Neither paced proof nor PER_FILE, whether [b] is still there or gone. */
    private fun assertNotAlone(
        others: List<EvidenceRules.Waiting>,
        b: EvidenceRules.Waiting,
        tx: Long,
        left: List<EvidenceRules.Left> = emptyList()
    ) {
        assertNull(alone(others + b, tx, left))
        val gone = attribute(others + b.copy(gone = true), tx, left)
        assertTrue("${gone[b.id]}", gone[b.id] != EvidenceRules.Attribution.PER_FILE)
    }

    @Test
    fun `an AGED or VERIFIED copy still waiting means nothing is alone`() {
        // Copy 1 has sat in the folder for twelve days (AGED), or its batch
        // was paid for by camera photos (VERIFIED). Ente can still send it, so
        // fresh copy 2 released "alone" beside it is not alone.
        assertNull(alone(listOf(graded(1), copy(2)), tx = 3_000_000))
    }

    @Test
    fun `a pace match never upgrades a graded copy`() {
        assertNull(alone(listOf(graded(1, hoursAgo = 1)), tx = 3_000_000))
    }

    @Test
    fun `a vanished copy beside a graded one is never its own proof`() {
        // Ente sent graded copy 1 (the same size) and never had copy 2, which
        // the person then deleted. The bytes cover copy 2 - nothing more.
        val result = attribute(listOf(graded(1), copy(2, gone = true)), tx = 3_000_000)
        assertEquals(EvidenceRules.Attribution.BYTES_SENT, result[2L])
        // A graded copy is neither settled nor judged here.
        assertNull(result[1L])
    }

    @Test
    fun `a big AGED copy sending part of itself is not a small copy's proof`() {
        // A 40 MB video copy is AGED because Ente keeps failing to finish it.
        // Fresh 5 MB copy 2 goes out alone; Ente starts the video again,
        // sends 5 MB and is killed. Copy 2 was never sent.
        val aged = graded(1, hoursAgo = 30 * 24).copy(bytes = 40_000_000)
        val b = copy(2, bytes = 5_000_000, hoursAgo = 0)
        assertTrue(EvidenceRules.confirmedPaced(5_000_000, b.bytes))
        assertNotAlone(listOf(aged), b, tx = 5_000_000)
    }

    @Test
    fun `a VERIFIED copy verified long ago still leaves nothing alone`() {
        // Verified a month ago on camera photos and still in the folder. Its
        // size does not matter: any part of it may be what went out.
        val b = copy(2, bytes = 3_000_000, hoursAgo = 0)
        for (bytes in listOf(3_200_000L, 1_000_000L, 20_000_000L, 0L)) {
            val v = verified(1, bytes = bytes)
            for (tx in listOf(b.bytes, v.bytes + b.bytes, 4_600_000L)) {
                if (EvidenceRules.confirmedPaced(tx, b.bytes)) assertNotAlone(listOf(v), b, tx)
            }
        }
    }

    @Test
    fun `a VERIFIED copy carried over from 12_1 still competes`() {
        // Its batch was verified days before the upgrade; a restored row is
        // dated by the restore. Either way it is still in the folder.
        for (verifiedHoursAgo in listOf(5 * 24L, 7L, 0L)) {
            val v = verified(1, bytes = 3_000_000, verifiedHoursAgo = verifiedHoursAgo)
            assertNotAlone(listOf(v), copy(2, hoursAgo = 0), tx = 3_000_000)
        }
    }

    @Test
    fun `any number of graded copies leaves nothing alone`() {
        val b = copy(9, bytes = 3_000_000, hoursAgo = 0)
        for (n in 1..4) {
            val neighbours = (1L..n.toLong()).map { verified(it, bytes = 20_000_000) }
            assertNotAlone(neighbours, b, tx = b.bytes)
        }
    }

    // ---- copies that left the folder during the window ----------------------

    private fun left(id: Long, hoursAgo: Long, provenHoursAgo: Long? = null) = EvidenceRules.Left(
        id = id,
        leftAt = now - hoursAgo * hour,
        provenAt = provenHoursAgo?.let { now - it * hour }
    )

    @Test
    fun `a copy that went during the window leaves nothing alone`() {
        // AGED copy 1, 3.2 MB, was in the folder when 3 MB copy 2 went out.
        // Ente sent copy 1, and copy 1 then went (deleted by hand, or by
        // Ente's free-up): what went out is its size, and copy 2 has had none.
        val b = copy(2, bytes = 3_000_000, hoursAgo = 2)
        val went = left(1, hoursAgo = 1)
        assertTrue(EvidenceRules.sharedWindow(went, b.releasedAt))
        assertNotAlone(emptyList(), b, tx = 3_200_000, left = listOf(went))
        // Whatever it carried and whenever it was graded after copy 2 went
        // out - even per-file proof, which it may have earned with these
        // very bytes.
        assertNotAlone(emptyList(), b, tx = 3_200_000, left = listOf(left(1, hoursAgo = 1, provenHoursAgo = 1)))
        // Leaving the moment copy 2 went out is inside the window too.
        val atRelease = EvidenceRules.Left(id = 1, leftAt = b.releasedAt, provenAt = null)
        assertNotAlone(emptyList(), b, tx = 3_200_000, left = listOf(atRelease))
    }

    @Test
    fun `a neighbour sent back to the queue mid-window leaves nothing alone`() {
        // N (40 MB) and B (5 MB) both went out ungraded. Ente sent 5 MB of N,
        // then N was deleted in the gallery and sent back to the queue, its
        // release time cleared. B is now the only copy waiting, and the 5 MB
        // match its size - every byte of it N's. N is read by when it left.
        val b = copy(2, bytes = 5_000_000, hoursAgo = 2)
        val resent = left(1, hoursAgo = 1)
        assertNotAlone(emptyList(), b, tx = 5_000_000, left = listOf(resent))
        // A pending copy repaired back to the queue is read the same way.
        assertNotAlone(emptyList(), b, tx = 5_000_000, left = listOf(left(3, hoursAgo = 0)))
    }

    @Test
    fun `a copy proved before the window does not count`() {
        // Ente had copy 1 before copy 2 went out; it left the folder later
        // (Ente's free-up), but Ente does not send a file it already has.
        val b = copy(2, bytes = 3_000_000, hoursAgo = 2)
        val proved = left(1, hoursAgo = 1, provenHoursAgo = 3)
        assertFalse(EvidenceRules.sharedWindow(proved, b.releasedAt))
        assertEquals(2L, alone(listOf(b), tx = 3_000_000, left = listOf(proved))?.id)
        val gone = attribute(listOf(b.copy(gone = true)), tx = 3_000_000, left = listOf(proved))
        assertEquals(EvidenceRules.Attribution.PER_FILE, gone[2L])
    }

    @Test
    fun `a copy that left before the window does not count`() {
        val b = copy(2, bytes = 3_000_000, hoursAgo = 2)
        val before = left(1, hoursAgo = 3)
        assertFalse(EvidenceRules.sharedWindow(before, b.releasedAt))
        assertEquals(2L, alone(listOf(b), tx = 3_000_000, left = listOf(before))?.id)
    }

    @Test
    fun `with no other copy the rule is the single-copy one`() {
        val b = copy(1, bytes = 3_000_000, hoursAgo = 1)
        assertEquals(1L, alone(listOf(b), tx = 3_100_000)?.id)
        assertEquals(EvidenceRules.Attribution.PER_FILE, attribute(listOf(b.copy(gone = true)), tx = 3_100_000)[1L])
        assertNull(alone(listOf(b), tx = 2_000_000))
        assertNull(alone(listOf(b), tx = 5_000_000))
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
        // A copy that had only aged, or whose batch's bytes may have been
        // camera photos, has no per-file proof to protect: Ente's free-up
        // taking it is the proof it was missing.
        assertTrue(collected(evidence = Evidence.AGED))
        assertTrue(collected(evidence = Evidence.VERIFIED))
    }

    @Test
    fun `graded copies Home sends the person to confirm are confirmed`() {
        // Home offers "Confirm uploads" while AGED or VERIFIED copies wait,
        // because traffic can no longer prove anything beside them. The
        // return must then credit them, or the person is told that nothing
        // left the folder and sent round the same loop again.
        for (ev in listOf(Evidence.NONE, Evidence.AGED, Evidence.VERIFIED)) {
            assertTrue("$ev is held for the return", held(evidence = ev))
            assertTrue("$ev is collected", collected(evidence = ev))
            // Still only when Ente's traffic, where readable, covers it.
            assertFalse("$ev needs its bytes", collected(evidence = ev, tx = 899))
            assertTrue("$ev with its bytes", collected(evidence = ev, tx = 900))
        }
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
        assertFalse(collected(evidence = Evidence.CONFIRMED_PACED))
        assertFalse(collected(evidence = Evidence.CONFIRMED_EXACT))
    }

    private fun held(
        window: EvidenceRules.ConfirmWindow? = this.window,
        id: Long = 1,
        at: Long = now,
        evidence: Evidence = Evidence.NONE,
        ours: Boolean = false
    ) = EvidenceRules.heldForReturn(window, id, at, evidence, ours)

    @Test
    fun `while the window is open other passes leave its copies for the return`() {
        // The hourly worker runs while the person is on Ente's free-up
        // screen: the copies Ente is collecting are not re-sent.
        assertTrue(held())
        assertTrue(held(evidence = Evidence.AGED))
        assertTrue(held(evidence = Evidence.VERIFIED))
        // Missing at the tap, our own deletion, or per-file proof: judged
        // normally.
        assertFalse(held(id = 2))
        assertFalse(held(ours = true))
        assertFalse(held(evidence = Evidence.CONFIRMED_PACED))
        assertFalse(held(evidence = Evidence.CONFIRMED_EXACT))
        assertFalse(held(window = null))
    }

    @Test
    fun `a closed window holds nothing, so no copy waits on it for good`() {
        assertTrue(EvidenceRules.isOpen(window, tapAt + Defaults.CONFIRM_WINDOW_MS))
        assertFalse(EvidenceRules.isOpen(window, tapAt + Defaults.CONFIRM_WINDOW_MS + 1))
        assertFalse(held(at = tapAt + Defaults.CONFIRM_WINDOW_MS + 1))
        assertFalse(held(at = tapAt - 1))
    }

    @Test
    fun `only a held copy can be collected`() {
        for (id in listOf(1L, 2L)) for (ev in Evidence.entries) for (ours in listOf(true, false)) {
            if (collected(id = id, evidence = ev, ours = ours)) assertTrue(held(id = id, evidence = ev, ours = ours))
        }
    }

    @Test
    fun `readable traffic must also cover the copy`() {
        assertFalse(collected(tx = 0))
        assertTrue(collected(tx = 1000))
    }
}
