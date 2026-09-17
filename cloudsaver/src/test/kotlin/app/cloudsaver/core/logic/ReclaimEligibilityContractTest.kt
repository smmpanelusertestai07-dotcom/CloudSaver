package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exact shape of a file that may be offered for removal, pinned on the JVM.
 *
 * This is what FreeUpConsentE2eTest was proving by building the same thing in a
 * real database on a real device: that a backed-up original, thirty days old,
 * with a matching ledger entry and per-file evidence, is eligible - and that
 * removing any single one of those conditions refuses it, naming which.
 *
 * The device could tell us that. It could not tell us quickly: each answer cost
 * an emulator boot and a twenty-minute suite, and every failure it reported all
 * day turned out to be the test's own assumption rather than this rule. The
 * rule itself is pure, so it belongs here, where it answers in a millisecond
 * and cannot be confused by a screen that had not drawn yet.
 */
class ReclaimEligibilityContractTest {

    /** Exactly what the end-to-end suite seeds, field for field. */
    private fun seeded(
        state: ItemState = ItemState.DONE,
        evidence: Evidence = Evidence.CONFIRMED_EXACT,
        hasLedgerEntry: Boolean = true,
        ledgerHashMatches: Boolean = true,
        originalPresent: Boolean = true,
        inExcludedAlbum: Boolean = false,
        isFavourite: Boolean = false,
        confirmedAgeDays: Int = 60,
        addedDaysAgo: Int = 60,
        sizeBytes: Long = 3L * 1024 * 1024
    ) = ReclaimRules.Candidate(
        id = 1L,
        fingerprint = "refuse00010000000000",
        sizeBytes = sizeBytes,
        optimisedBytes = sizeBytes / 4,
        evidence = evidence,
        confirmedAgeDays = confirmedAgeDays,
        state = state,
        hasLedgerEntry = hasLedgerEntry,
        ledgerHashMatches = ledgerHashMatches,
        originalPresent = originalPresent,
        inExcludedAlbum = inExcludedAlbum,
        isFavourite = isFavourite,
        addedDaysAgo = addedDaysAgo,
        isVideo = false,
        album = "CloudSaverTest"
    )

    private fun refuse(c: ReclaimRules.Candidate, cloudHealthy: Boolean = true) =
        ReclaimRules.refuse(c, cloudHealthy = cloudHealthy, allowVerifiedBySize = false)

    @Test
    fun theFileTheSuiteSeedsIsEligible() {
        assertNull(
            "the row the end-to-end suite builds must be offered for removal, " +
                "or that suite is exercising an empty screen",
            refuse(seeded())
        )
    }

    // ---- and every single condition that takes it away, named ---------------

    @Test
    fun anOriginalThatHasGoneIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.ORIGINAL_GONE,
            refuse(seeded(originalPresent = false))
        )

    @Test
    fun aFileStillWaitingIsRefused() =
        assertEquals(ReclaimRules.Refusal.WRONG_STATE, refuse(seeded(state = ItemState.NEW)))

    @Test
    fun aFileMidOptimiseIsRefused() =
        assertEquals(ReclaimRules.Refusal.WRONG_STATE, refuse(seeded(state = ItemState.STAGED)))

    @Test
    fun anExcludedAlbumIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.EXCLUDED_ALBUM,
            refuse(seeded(inExcludedAlbum = true))
        )

    /**
     * The one that would have explained a whole afternoon: with the cloud
     * unhealthy nothing is eligible, the Free up screen has no rows, and every
     * wait for it times out saying nothing about why.
     */
    @Test
    fun anUnhealthyCloudRefusesEverything() =
        assertEquals(
            ReclaimRules.Refusal.CLOUD_UNHEALTHY,
            refuse(seeded(), cloudHealthy = false)
        )

    @Test
    fun noLedgerEntryIsRefused() =
        assertEquals(ReclaimRules.Refusal.NO_LEDGER, refuse(seeded(hasLedgerEntry = false)))

    @Test
    fun aCopyThatChangedIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.HASH_CHANGED,
            refuse(seeded(ledgerHashMatches = false))
        )

    @Test
    fun evidenceThatIsOnlyAnAgeIsRefusedWhenSizeProofIsNotAllowed() =
        assertEquals(ReclaimRules.Refusal.NOT_CONFIRMED, refuse(seeded(evidence = Evidence.AGED)))

    @Test
    fun aConfirmationYoungerThanThirtyDaysIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.TOO_RECENT,
            refuse(seeded(confirmedAgeDays = ReclaimRules.MIN_CONFIRM_AGE_DAYS - 1))
        )

    @Test
    fun aPhotoAddedLessThanThirtyDaysAgoIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.TOO_RECENT,
            refuse(seeded(addedDaysAgo = ReclaimRules.MIN_CONFIRM_AGE_DAYS - 1))
        )

    @Test
    fun aFavouriteIsRefused() =
        assertEquals(ReclaimRules.Refusal.FAVOURITE, refuse(seeded(isFavourite = true)))

    @Test
    fun aFileTooSmallToBeWorthItIsRefused() =
        assertEquals(
            ReclaimRules.Refusal.TOO_SMALL,
            refuse(seeded(sizeBytes = ReclaimRules.MIN_SIZE_BYTES - 1))
        )

    /**
     * Thirty days is the promise the help text makes. If the constant moves,
     * the sentence people were shown stops being true.
     */
    @Test
    fun thePromisedWaitIsThirtyDaysAndTheFloorIsTwoMegabytes() {
        assertEquals(30, ReclaimRules.MIN_CONFIRM_AGE_DAYS)
        assertEquals(2L * 1024 * 1024, ReclaimRules.MIN_SIZE_BYTES)
    }
}
