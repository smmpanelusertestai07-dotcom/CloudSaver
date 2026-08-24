package app.cloudsaver.core.logic

/**
 * When an original may be offered for removal, and what removing it means.
 *
 * This is the only place in the app where a user's own photo can be destroyed,
 * so every rule here errs towards refusing. Eligibility is re-checked at the
 * moment of action rather than when the list was built: a list can sit open
 * for an hour while the cloud app is uninstalled underneath it.
 */
object ReclaimRules {

    /** A confirmation has to settle before the original is offered. */
    const val MIN_CONFIRM_AGE_DAYS = 30

    /** Nothing under this is worth the risk of a delete dialog. */
    const val MIN_SIZE_BYTES = 2L * 1024 * 1024

    /** Above either threshold, the numbers are spelled out a second time. */
    const val LARGE_BATCH_BYTES = 20L * 1_000_000_000
    const val LARGE_BATCH_SHARE = 0.5

    /** MediaStore refuses a request with more URIs than this. */
    const val MAX_URIS_PER_REQUEST = 500

    /**
     * What the user chose to happen to a batch.
     *
     * The default keeps a viewable file in the gallery, because "my photos
     * disappeared" is the complaint that ends trust in a storage app, even
     * when the cloud has them.
     */
    enum class Mode {
        /** Original removed, the optimised copy stays in the gallery. */
        REPLACE_WITH_LIGHT,
        /** Original removed, nothing kept locally. */
        FREE_UP_FULLY,
        /** Originals untouched; only confirmed copies in the upload folder go. */
        COPIES_ONLY
    }

    /** Everything about one candidate that the rules need. */
    data class Candidate(
        val id: Long,
        val fingerprint: String,
        val sizeBytes: Long,
        val optimisedBytes: Long,
        val evidence: Evidence,
        val confirmedAgeDays: Int,
        val state: ItemState,
        val hasLedgerEntry: Boolean,
        val ledgerHashMatches: Boolean,
        val originalPresent: Boolean,
        val inExcludedAlbum: Boolean,
        val isFavourite: Boolean,
        val addedDaysAgo: Int,
        val isVideo: Boolean,
        val album: String? = null,
        val capturedAtMs: Long = 0
    )

    /** Why a candidate was refused, so the result summary can say so. */
    enum class Refusal {
        NOT_CONFIRMED, TOO_RECENT, NO_LEDGER, HASH_CHANGED, CLOUD_UNHEALTHY,
        WRONG_STATE, ORIGINAL_GONE, EXCLUDED_ALBUM, FAVOURITE, TOO_SMALL
    }

    /**
     * The whole gate. [cloudHealthy] and [allowVerifiedBySize] come from
     * outside because they are properties of the moment, not of the file.
     */
    fun refuse(
        c: Candidate,
        cloudHealthy: Boolean,
        allowVerifiedBySize: Boolean,
        skipFavourites: Boolean = true,
        skipSmall: Boolean = true
    ): Refusal? = when {
        !c.originalPresent -> Refusal.ORIGINAL_GONE
        c.state == ItemState.UNKNOWN || c.state == ItemState.SKIP ||
            c.state == ItemState.NEW || c.state == ItemState.STAGED -> Refusal.WRONG_STATE
        c.state.isReclaimed -> Refusal.WRONG_STATE
        c.inExcludedAlbum -> Refusal.EXCLUDED_ALBUM
        !cloudHealthy -> Refusal.CLOUD_UNHEALTHY
        !c.hasLedgerEntry -> Refusal.NO_LEDGER
        !c.ledgerHashMatches -> Refusal.HASH_CHANGED
        !qualifies(c.evidence, allowVerifiedBySize) -> Refusal.NOT_CONFIRMED
        c.confirmedAgeDays < MIN_CONFIRM_AGE_DAYS -> Refusal.TOO_RECENT
        c.addedDaysAgo < MIN_CONFIRM_AGE_DAYS -> Refusal.TOO_RECENT
        skipFavourites && c.isFavourite -> Refusal.FAVOURITE
        skipSmall && c.sizeBytes < MIN_SIZE_BYTES -> Refusal.TOO_SMALL
        else -> null
    }

    fun isEligible(
        c: Candidate,
        cloudHealthy: Boolean,
        allowVerifiedBySize: Boolean,
        skipFavourites: Boolean = true,
        skipSmall: Boolean = true
    ): Boolean = refuse(c, cloudHealthy, allowVerifiedBySize, skipFavourites, skipSmall) == null

    /**
     * Which grades count. A batch-level VERIFIED says a day's bytes went out,
     * not that this photo did, so it only ever counts behind an explicit
     * opt-in - and even then the 30-day wait still applies.
     */
    private fun qualifies(evidence: Evidence, allowVerifiedBySize: Boolean): Boolean = when (evidence) {
        Evidence.CONFIRMED_EXACT, Evidence.CONFIRMED_PACED -> true
        Evidence.VERIFIED -> allowVerifiedBySize
        Evidence.AGED, Evidence.NONE -> false
    }

    /**
     * What a mode actually frees, so the three can be compared before one is
     * chosen. Replacing keeps the copy, so it frees the difference; freeing
     * fully frees the original outright; copies-only never touches originals.
     */
    fun savedBytes(items: List<Candidate>, mode: Mode): Long = when (mode) {
        Mode.REPLACE_WITH_LIGHT ->
            items.sumOf { (it.sizeBytes - it.optimisedBytes).coerceAtLeast(0L) }
        Mode.FREE_UP_FULLY -> items.sumOf { it.sizeBytes }
        Mode.COPIES_ONLY -> items.sumOf { it.optimisedBytes }
    }

    /**
     * Pick largest-first until the target is met.
     *
     * Largest-first because the point is to free a number, and reaching it
     * with ten files instead of four hundred means ten decisions to check
     * rather than four hundred.
     */
    fun selectForTarget(
        items: List<Candidate>,
        targetBytes: Long,
        mode: Mode
    ): List<Candidate> {
        if (targetBytes <= 0) return emptyList()
        val chosen = mutableListOf<Candidate>()
        var freed = 0L
        for (item in items.sortedByDescending { it.sizeBytes }) {
            if (freed >= targetBytes) break
            chosen += item
            freed = savedBytes(chosen, mode)
        }
        return chosen
    }

    /** A batch big enough to be worth stating in words before the dialog. */
    fun needsSecondConfirmation(
        selected: List<Candidate>,
        eligibleCount: Int,
        mode: Mode,
        permanent: Boolean
    ): Boolean {
        if (permanent) return true
        if (savedBytes(selected, mode) >= LARGE_BATCH_BYTES) return true
        if (eligibleCount <= 0) return false
        return selected.size.toDouble() / eligibleCount >= LARGE_BATCH_SHARE
    }

    /** MediaStore refuses oversized requests, so the batch is split. */
    fun batches(uris: List<String>, size: Int = MAX_URIS_PER_REQUEST): List<List<String>> =
        if (uris.isEmpty()) emptyList() else uris.chunked(size.coerceAtLeast(1))
}
