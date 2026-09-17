package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReclaimRulesTest {

    private fun candidate(
        id: Long = 1,
        mb: Long = 10,
        optimisedMb: Long = 3,
        evidence: Evidence = Evidence.CONFIRMED_EXACT,
        confirmedAgeDays: Int = 60,
        state: ItemState = ItemState.DONE,
        hasLedger: Boolean = true,
        hashMatches: Boolean = true,
        present: Boolean = true,
        excluded: Boolean = false,
        favourite: Boolean = false,
        addedDaysAgo: Int = 400,
        isVideo: Boolean = false
    ) = ReclaimRules.Candidate(
        id = id,
        fingerprint = "fp-$id",
        sizeBytes = mb * 1_000_000,
        optimisedBytes = optimisedMb * 1_000_000,
        evidence = evidence,
        confirmedAgeDays = confirmedAgeDays,
        state = state,
        hasLedgerEntry = hasLedger,
        ledgerHashMatches = hashMatches,
        originalPresent = present,
        inExcludedAlbum = excluded,
        isFavourite = favourite,
        addedDaysAgo = addedDaysAgo,
        isVideo = isVideo
    )

    private fun refuse(c: ReclaimRules.Candidate, healthy: Boolean = true, verified: Boolean = false) =
        ReclaimRules.refuse(c, healthy, verified)

    // ---- the gate ------------------------------------------------------------

    @Test
    fun `a settled, confirmed, present original is eligible`() {
        assertNull(refuse(candidate()))
    }

    @Test
    fun `weaker evidence is refused, and stays refused with age`() {
        assertEquals(
            ReclaimRules.Refusal.NOT_CONFIRMED,
            refuse(candidate(evidence = Evidence.AGED, confirmedAgeDays = 3650))
        )
        assertEquals(
            ReclaimRules.Refusal.NOT_CONFIRMED,
            refuse(candidate(evidence = Evidence.NONE))
        )
    }

    @Test
    fun `batch evidence counts only behind the opt-in, and still waits`() {
        val c = candidate(evidence = Evidence.VERIFIED)
        assertEquals(ReclaimRules.Refusal.NOT_CONFIRMED, refuse(c, verified = false))
        assertNull(refuse(c, verified = true))
        assertEquals(
            ReclaimRules.Refusal.TOO_RECENT,
            refuse(candidate(evidence = Evidence.VERIFIED, confirmedAgeDays = 10), verified = true)
        )
    }

    @Test
    fun `a fresh confirmation has to settle first`() {
        assertEquals(
            ReclaimRules.Refusal.TOO_RECENT,
            refuse(candidate(confirmedAgeDays = ReclaimRules.MIN_CONFIRM_AGE_DAYS - 1))
        )
        assertNull(refuse(candidate(confirmedAgeDays = ReclaimRules.MIN_CONFIRM_AGE_DAYS)))
    }

    @Test
    fun `a photo taken last week is never offered, however confirmed`() {
        assertEquals(
            ReclaimRules.Refusal.TOO_RECENT,
            refuse(candidate(addedDaysAgo = 7))
        )
    }

    @Test
    fun `no ledger entry, or a changed hash, means no proof`() {
        assertEquals(ReclaimRules.Refusal.NO_LEDGER, refuse(candidate(hasLedger = false)))
        assertEquals(ReclaimRules.Refusal.HASH_CHANGED, refuse(candidate(hashMatches = false)))
    }

    @Test
    fun `an unhealthy cloud stops everything`() {
        // The list can sit open while the cloud app is uninstalled underneath
        // it, which is exactly why this is re-checked at action time.
        assertEquals(
            ReclaimRules.Refusal.CLOUD_UNHEALTHY,
            refuse(candidate(), healthy = false)
        )
    }

    @Test
    fun `unfinished, skipped and already-reclaimed items are not candidates`() {
        for (state in listOf(
            ItemState.NEW, ItemState.STAGED, ItemState.SKIP, ItemState.UNKNOWN,
            ItemState.FREED, ItemState.FREED_KEPT
        )) {
            assertEquals(
                "$state must be refused",
                ReclaimRules.Refusal.WRONG_STATE,
                refuse(candidate(state = state))
            )
        }
    }

    @Test
    fun `a missing original is refused before anything else is considered`() {
        assertEquals(
            ReclaimRules.Refusal.ORIGINAL_GONE,
            refuse(candidate(present = false, hasLedger = false, evidence = Evidence.NONE))
        )
    }

    @Test
    fun `favourites and tiny files are skipped by default and can be included`() {
        assertEquals(ReclaimRules.Refusal.FAVOURITE, refuse(candidate(favourite = true)))
        assertEquals(ReclaimRules.Refusal.TOO_SMALL, refuse(candidate(mb = 1)))
        assertNull(
            ReclaimRules.refuse(
                candidate(favourite = true, mb = 1), true, false,
                skipFavourites = false, skipSmall = false
            )
        )
    }

    // ---- what each mode frees ------------------------------------------------

    @Test
    fun `the three modes free three different amounts`() {
        val items = listOf(candidate(mb = 10, optimisedMb = 3), candidate(id = 2, mb = 20, optimisedMb = 5))
        assertEquals(22_000_000, ReclaimRules.savedBytes(items, ReclaimRules.Mode.REPLACE_WITH_LIGHT))
        assertEquals(30_000_000, ReclaimRules.savedBytes(items, ReclaimRules.Mode.FREE_UP_FULLY))
        assertEquals(8_000_000, ReclaimRules.savedBytes(items, ReclaimRules.Mode.COPIES_ONLY))
    }

    @Test
    fun `an optimised copy bigger than its original never frees a negative`() {
        val odd = candidate(mb = 3, optimisedMb = 5)
        assertEquals(0L, ReclaimRules.savedBytes(listOf(odd), ReclaimRules.Mode.REPLACE_WITH_LIGHT))
    }

    // ---- target selection ----------------------------------------------------

    @Test
    fun `a target picks largest-first and stops once it is met`() {
        val items = (1..5).map { candidate(id = it.toLong(), mb = it * 10L, optimisedMb = 0) }
        val chosen = ReclaimRules.selectForTarget(
            items, 80_000_000, ReclaimRules.Mode.FREE_UP_FULLY
        )
        // 50 + 40 clears 80; a third file would be more than asked for.
        assertEquals(listOf(50_000_000L, 40_000_000L), chosen.map { it.sizeBytes })
    }

    @Test
    fun `a target bigger than everything selects everything, not more`() {
        val items = (1..3).map { candidate(id = it.toLong(), mb = 10, optimisedMb = 0) }
        assertEquals(
            3,
            ReclaimRules.selectForTarget(items, 999_000_000, ReclaimRules.Mode.FREE_UP_FULLY).size
        )
    }

    @Test
    fun `a zero or negative target selects nothing`() {
        val items = listOf(candidate())
        assertTrue(ReclaimRules.selectForTarget(items, 0, ReclaimRules.Mode.FREE_UP_FULLY).isEmpty())
        assertTrue(ReclaimRules.selectForTarget(items, -1, ReclaimRules.Mode.FREE_UP_FULLY).isEmpty())
    }

    @Test
    fun `the target respects the mode it is selecting for`() {
        // Replacing frees only the difference, so it takes more files to hit
        // the same number than freeing fully does.
        val items = (1..6).map { candidate(id = it.toLong(), mb = 10, optimisedMb = 8) }
        val replace = ReclaimRules.selectForTarget(
            items, 8_000_000, ReclaimRules.Mode.REPLACE_WITH_LIGHT
        )
        val full = ReclaimRules.selectForTarget(items, 8_000_000, ReclaimRules.Mode.FREE_UP_FULLY)
        assertTrue(replace.size > full.size)
    }

    // ---- guards --------------------------------------------------------------

    @Test
    fun `a permanent delete always asks twice`() {
        assertTrue(
            ReclaimRules.needsSecondConfirmation(
                listOf(candidate()), 100, ReclaimRules.Mode.FREE_UP_FULLY, permanent = true
            )
        )
    }

    @Test
    fun `a huge batch asks twice even when only trashing`() {
        val huge = (1..30).map { candidate(id = it.toLong(), mb = 1000, optimisedMb = 0) }
        assertTrue(
            ReclaimRules.needsSecondConfirmation(
                huge, 10_000, ReclaimRules.Mode.FREE_UP_FULLY, permanent = false
            )
        )
    }

    @Test
    fun `taking most of what is eligible asks twice`() {
        val some = (1..6).map { candidate(id = it.toLong(), mb = 5, optimisedMb = 0) }
        assertTrue(
            ReclaimRules.needsSecondConfirmation(
                some, 10, ReclaimRules.Mode.FREE_UP_FULLY, permanent = false
            )
        )
        assertFalse(
            ReclaimRules.needsSecondConfirmation(
                some, 1000, ReclaimRules.Mode.FREE_UP_FULLY, permanent = false
            )
        )
    }

    // ---- batching ------------------------------------------------------------

    @Test
    fun `requests are split into chunks a person can read`() {
        val uris = (1..1201).map { "uri-$it" }
        val batches = ReclaimRules.batches(uris)
        assertEquals(3, batches.size)
        assertEquals(ReclaimRules.MAX_URIS_PER_REQUEST, batches[0].size)
        assertEquals(201, batches.last().size)
        assertEquals(uris.size, batches.sumOf { it.size })
    }

    @Test
    fun `nothing to send is no request at all`() {
        assertTrue(ReclaimRules.batches(emptyList<String>()).isEmpty())
    }
}
