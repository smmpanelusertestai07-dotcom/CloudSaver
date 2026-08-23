package app.cloudsaver.core.logic

/**
 * When transmitted bytes count as proof that a particular copy was uploaded.
 *
 * The app cannot see inside the cloud app; all it has is how many bytes that
 * app sent and whether the copy is still sitting in the upload folder. These
 * are the thresholds that turn those two facts into a claim, and every one of
 * them errs towards saying less than it knows: the strongest grades are what
 * eventually offers a user's original for deletion.
 */
object EvidenceRules {

    /** A batch is verified once nine tenths of its bytes have gone out. */
    const val BATCH_MIN_RATIO = 0.9

    /** The disappearance case needs the same nine tenths for that one file. */
    const val EXACT_MIN_RATIO = 0.9

    /**
     * A paced release is confirmed when traffic lands in this window. The
     * upper bound matters as much as the lower: far more than the file's size
     * means something else was uploading too, so the number proves nothing
     * about this file.
     */
    const val PACED_MIN_RATIO = 0.85
    const val PACED_MAX_RATIO = 1.6

    /** Originals may only be reclaimed once their copy is this old. */
    const val RECLAIM_MIN_DAYS = 30

    /** A copy that vanished with no traffic is re-sent at most this often. */
    const val MAX_RESENDS = 2

    /** Integer maths; no float rounding surprises at the boundary. */
    fun batchVerified(txBytes: Long, batchBytes: Long): Boolean =
        batchBytes > 0 && txBytes >= 0 && txBytes * 10 >= batchBytes * 9

    /** The copy is gone from the folder and its bytes were transmitted. */
    fun confirmedExact(txSinceRelease: Long, fileBytes: Long): Boolean =
        fileBytes > 0 && txSinceRelease * 10 >= fileBytes * 9

    /** The copy went out alone and the traffic matches its size. */
    fun confirmedPaced(txSinceRelease: Long, fileBytes: Long): Boolean {
        if (fileBytes <= 0 || txSinceRelease < 0) return false
        val ratio = txSinceRelease.toDouble() / fileBytes
        return ratio >= PACED_MIN_RATIO && ratio <= PACED_MAX_RATIO
    }

    /**
     * What a released copy's disappearance means.
     *
     * A cloud with a free-up feature deletes its own uploads once they are
     * safe, so a file vanishing while that app was transmitting is the normal
     * success case - not the user losing data. Vanishing with no traffic at
     * all is the opposite, and worth re-sending a couple of times before
     * giving up rather than looping forever.
     */
    fun onCopyMissing(
        appDeletedIt: Boolean,
        txSinceRelease: Long,
        fileBytes: Long,
        resendCount: Int
    ): MissingVerdict = when {
        appDeletedIt -> MissingVerdict.WE_DELETED_IT
        confirmedExact(txSinceRelease, fileBytes) -> MissingVerdict.PROOF_OF_UPLOAD
        resendCount >= MAX_RESENDS -> MissingVerdict.GIVE_UP
        else -> MissingVerdict.RESEND
    }

    enum class MissingVerdict { PROOF_OF_UPLOAD, RESEND, GIVE_UP, WE_DELETED_IT }

    /**
     * Whether an original may be offered for reclaim.
     *
     * Only the two per-file grades qualify, and they are not equal. Seeing the
     * copy leave the upload folder while the cloud app was transmitting is an
     * observation; a byte count that happens to match the file is an
     * inference, so that one has to settle for a month first. A batch-level
     * VERIFIED never qualifies here at all: it says a day's worth of bytes
     * went out, not that this photo did.
     */
    fun mayReclaimOriginal(evidence: Evidence, copyAgeDays: Int): Boolean = when (evidence) {
        Evidence.CONFIRMED_EXACT -> true
        Evidence.CONFIRMED_PACED -> copyAgeDays >= RECLAIM_MIN_DAYS
        else -> false
    }

    /** Whether the app may delete its own copy to reclaim device space. */
    fun mayDeleteCopy(evidence: Evidence, copyAgeDays: Int): Boolean = when (evidence) {
        Evidence.CONFIRMED_EXACT, Evidence.CONFIRMED_PACED, Evidence.VERIFIED ->
            copyAgeDays >= Defaults.KEEP_MIN_DAYS
        Evidence.AGED -> copyAgeDays >= Defaults.AGED_DAYS
        Evidence.NONE -> false
    }
}
