package app.entesaver.core.logic

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

    /**
     * Nine tenths of a batch's bytes, compared in integers: no float rounding
     * surprise at the boundary, and the threshold written down once instead
     * of also sitting in a constant that nothing reads.
     */
    fun batchVerified(txBytes: Long, batchBytes: Long): Boolean =
        batchBytes > 0 && txBytes >= 0 && txBytes * 10 >= batchBytes * 9

    /** The copy is gone from the folder, and the same nine tenths went out. */
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
     * safe, so a file vanishing while that app was transmitting can be the
     * success case. But so can a person clearing the folder in the gallery,
     * and Ente's byte count covers everything it sends - camera photos and
     * the other copies too. So only traffic [attributeTraffic] could pin on
     * this one copy is proof; bytes that merely went out somewhere are worth
     * the batch grade at most. Vanishing with nothing to show for it is
     * worth re-sending a couple of times before giving up rather than
     * looping forever.
     */
    fun onCopyMissing(
        appDeletedIt: Boolean,
        attribution: Attribution,
        resendCount: Int
    ): MissingVerdict = when {
        appDeletedIt -> MissingVerdict.WE_DELETED_IT
        attribution == Attribution.PER_FILE -> MissingVerdict.PROOF_OF_UPLOAD
        attribution == Attribution.BYTES_SENT -> MissingVerdict.BYTES_SENT
        resendCount >= MAX_RESENDS -> MissingVerdict.GIVE_UP
        else -> MissingVerdict.RESEND
    }

    /**
     * BYTES_SENT is "enough went out to cover it, but not provably this
     * file": the copy is not sent again, and it carries no grade stronger
     * than a batch's.
     */
    enum class MissingVerdict { PROOF_OF_UPLOAD, BYTES_SENT, RESEND, GIVE_UP, WE_DELETED_IT }

    /** One released copy of ours that has no evidence yet. */
    data class Waiting(
        val id: Long,
        val releasedAt: Long,
        val bytes: Long,
        val gone: Boolean
    )

    /** How much of Ente's traffic a vanished copy can claim as its own. */
    enum class Attribution { PER_FILE, BYTES_SENT, UNPROVEN }

    /**
     * The copy paced proof may judge, or null.
     *
     * Only ever the one copy of ours still waiting - and every waiting copy
     * counts, timed out or not. A copy that timed out is still in the folder
     * and Ente can still send it, so a newer copy released "alone" next to
     * it would be credited with its bytes. The copy itself must also still
     * be inside its window: past it, the count has had hours to pick up
     * camera uploads.
     */
    fun aloneInFlight(waiting: List<Waiting>, now: Long): Waiting? {
        val only = waiting.singleOrNull() ?: return null
        return if (Pacing.isTimedOut(only.releasedAt, now)) null else only
    }

    /**
     * Which vanished copies Ente's traffic can pay for.
     *
     * [txSinceEarliest] is everything Ente sent since the oldest waiting
     * copy went out, or null when it cannot be measured. Every waiting copy
     * competes for those bytes - the ones still in the folder too - and they
     * are settled oldest first against one running total, as batches are: a
     * transmitted byte can only pay for one copy.
     *
     * Even covered, that is only BYTES_SENT: the bytes may have been camera
     * photos. PER_FILE needs what paced proof needs - the copy was the only
     * one of ours waiting, still inside its window, and the traffic matches
     * its size both ways.
     */
    fun attributeTraffic(
        waiting: List<Waiting>,
        txSinceEarliest: Long?,
        now: Long
    ): Map<Long, Attribution> {
        val result = HashMap<Long, Attribution>()
        for (copy in waiting) if (copy.gone) result[copy.id] = Attribution.UNPROVEN
        if (txSinceEarliest == null || txSinceEarliest < 0) return result
        val alone = aloneInFlight(waiting, now)
        var required = 0L
        for (copy in waiting.sortedWith(compareBy({ it.releasedAt }, { it.id }))) {
            // A copy of unknown size cannot be covered by anything, and once
            // the total stops covering one copy it covers none after it.
            if (copy.bytes <= 0) break
            required += copy.bytes
            if (!batchVerified(txSinceEarliest, required)) break
            if (!copy.gone) continue
            result[copy.id] = if (alone?.id == copy.id && confirmedPaced(txSinceEarliest, copy.bytes)) {
                Attribution.PER_FILE
            } else {
                Attribution.BYTES_SENT
            }
        }
        return result
    }

    /**
     * The moment "Confirm uploads" opened Ente, and which copies were still
     * in their folders right then - after a normal pass had already judged
     * everything missing before the tap.
     */
    data class ConfirmWindow(val openedAt: Long, val presentAtTap: Set<Long>)

    /**
     * Whether a copy missing on the return from Ente's free-up screen left
     * because Ente collected it.
     *
     * The return is the only evidence, so it is believed narrowly: only on
     * the one pass the return starts, only soon after the tap, only for a
     * copy that was still there at the tap, and never over a finding with
     * real proof behind it. Where Ente's traffic can be read, the copy's
     * bytes must also have gone out.
     */
    fun collectedByFreeUp(
        window: ConfirmWindow?,
        id: Long,
        now: Long,
        evidence: Evidence,
        appDeletedIt: Boolean,
        txSinceRelease: Long?,
        fileBytes: Long
    ): Boolean = window != null &&
        now >= window.openedAt &&
        now - window.openedAt <= Defaults.CONFIRM_WINDOW_MS &&
        id in window.presentAtTap &&
        !appDeletedIt &&
        (evidence == Evidence.NONE || evidence == Evidence.AGED) &&
        (txSinceRelease == null || confirmedExact(txSinceRelease, fileBytes))

    // Whether an original may be reclaimed is decided by ReclaimRules.refuse,
    // and whether one of the app's own copies may go by DeletePlanner. Both
    // used to have a second version here that nothing called, and the reclaim
    // one disagreed: it allowed an original the moment proof landed, where the
    // live rule makes every grade wait thirty days. An uncalled rule with its
    // own tests reads like the app's guarantee, so it is not kept here.
}
