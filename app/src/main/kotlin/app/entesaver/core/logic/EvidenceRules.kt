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

    /**
     * One released copy of ours without per-file proof.
     *
     * [graded] is a copy that already carries AGED or VERIFIED. Neither says
     * Ente has this file - time alone, or a batch's bytes that may have been
     * camera photos - so Ente may still send it, all of it or any part, at
     * any time it is in the folder, however long ago it was graded. A byte
     * match never upgrades it, and it is not settled in the running total:
     * its window can be days old.
     *
     * [verifiedAt] is set only on a VERIFIED copy: when its batch was
     * verified. Only [Pacing.releaseLimit] reads it, for how long the copy
     * holds a release slot; it never lets the copy stop competing.
     */
    data class Waiting(
        val id: Long,
        val releasedAt: Long,
        val bytes: Long,
        val gone: Boolean,
        val graded: Boolean = false,
        val verifiedAt: Long? = null
    )

    /**
     * A copy of ours that was released and has since left the folder - gone,
     * finished, freed, skipped or sent back to the queue.
     *
     * [leftAt] is when it left: recorded by the write that took it out of the
     * folder, and moved by no later one. A copy that left before that was
     * recorded is dated by its row's last change, which is never earlier.
     * [provenAt] is when the copy got per-file proof, or null without it.
     */
    data class Left(val id: Long, val leftAt: Long, val provenAt: Long?)

    /** How much of Ente's traffic a vanished copy can claim as its own. */
    enum class Attribution { PER_FILE, BYTES_SENT, UNPROVEN }

    /**
     * Whether [copy] may have been in the folder, and Ente sending it, at
     * any time since [since].
     *
     * It left at or after [since], so for some of that time it was there -
     * unless it already had per-file proof before then: Ente had it, and
     * does not send a file twice.
     */
    fun sharedWindow(copy: Left, since: Long): Boolean =
        copy.leftAt >= since && (copy.provenAt == null || copy.provenAt >= since)

    /**
     * The copy paced proof may judge for [tx], what Ente sent since it went
     * out, or null.
     *
     * Only when no other copy of ours could have sent any of [tx]: the one
     * copy without a grade, still inside its window - past it, the count has
     * had hours to pick up camera uploads - with nothing else in the folder
     * waiting for proof, graded or not, and nothing in [left] that was there
     * during its window. And only when [tx] matches its size both ways.
     *
     * An upload can be cut short, resumed, or read halfway, so a neighbour
     * can account for any amount of traffic, from none of its bytes to all
     * of them. No size comparison rules one out; only its absence does.
     */
    fun aloneInFlight(waiting: List<Waiting>, left: List<Left>, now: Long, tx: Long): Waiting? {
        val only = waiting.singleOrNull()?.takeIf { !it.graded } ?: return null
        if (Pacing.isTimedOut(only.releasedAt, now) || !confirmedPaced(tx, only.bytes)) return null
        if (left.any { it.id != only.id && sharedWindow(it, only.releasedAt) }) return null
        return only
    }

    /**
     * Which vanished copies Ente's traffic can pay for.
     *
     * [txSinceEarliest] is everything Ente sent since the oldest ungraded
     * copy went out, or null when it cannot be measured. Every ungraded copy
     * competes for those bytes - the ones still in the folder too - and they
     * are settled oldest first against one running total, as batches are: a
     * transmitted byte can only pay for one copy.
     *
     * Even covered, that is only BYTES_SENT: the bytes may have been camera
     * photos. PER_FILE needs what paced proof needs: [aloneInFlight] picks
     * the copy, from the same copies waiting and the same copies [left].
     */
    fun attributeTraffic(
        waiting: List<Waiting>,
        left: List<Left>,
        txSinceEarliest: Long?,
        now: Long
    ): Map<Long, Attribution> {
        val result = HashMap<Long, Attribution>()
        val ungraded = waiting.filter { !it.graded }
        for (copy in ungraded) if (copy.gone) result[copy.id] = Attribution.UNPROVEN
        if (txSinceEarliest == null || txSinceEarliest < 0) return result
        val alone = aloneInFlight(waiting, left, now, txSinceEarliest)
        var required = 0L
        for (copy in ungraded.sortedWith(compareBy({ it.releasedAt }, { it.id }))) {
            // A copy of unknown size cannot be covered by anything, and once
            // the total stops covering one copy it covers none after it.
            if (copy.bytes <= 0) break
            required += copy.bytes
            if (!batchVerified(txSinceEarliest, required)) break
            if (!copy.gone) continue
            result[copy.id] = if (alone?.id == copy.id) Attribution.PER_FILE else Attribution.BYTES_SENT
        }
        return result
    }

    /**
     * The moment "Confirm uploads" was tapped, and which copies were still in
     * their folders right then. A copy already missing is not in it, so the
     * normal rules judge it as if the button had never been tapped.
     */
    data class ConfirmWindow(val openedAt: Long, val presentAtTap: Set<Long>)

    /** Whether [window] still stands: soon after the tap, and not before it. */
    fun isOpen(window: ConfirmWindow?, now: Long): Boolean = window != null &&
        now >= window.openedAt &&
        now - window.openedAt <= Defaults.CONFIRM_WINDOW_MS

    /**
     * Whether a missing copy is left for the return from Ente to judge.
     *
     * While the person is on Ente's free-up screen, any other pass - the
     * hourly worker, the end of a compress run, a pass in a new process -
     * would judge the copies Ente is collecting by the normal rules and send
     * them again. So it leaves them exactly as they are, and grants nothing:
     * only the return pass may read the absence as Ente's. Once the window
     * has closed they are judged normally, so nothing waits on it for good.
     *
     * Every copy without per-file proof is held, graded ones too: AGED and
     * VERIFIED say nothing about this file reaching Ente, and Ente's free-up
     * removing it is exactly the per-file signal they lack. That is why Home
     * offers "Confirm uploads" while graded copies wait.
     */
    fun heldForReturn(
        window: ConfirmWindow?,
        id: Long,
        now: Long,
        evidence: Evidence,
        appDeletedIt: Boolean
    ): Boolean = window != null &&
        isOpen(window, now) &&
        id in window.presentAtTap &&
        !appDeletedIt &&
        !evidence.isPerFile

    /**
     * Whether a copy missing on the return from Ente's free-up screen left
     * because Ente collected it.
     *
     * The return is the only evidence, so it is believed narrowly: only on
     * the one pass the return starts, only soon after the tap, only for a
     * copy that was still there at the tap, and never over per-file proof,
     * which needs nothing more. A graded copy - AGED or VERIFIED - is
     * believed like an ungraded one: neither grade is proof Ente has this
     * file. Where Ente's traffic can be read, the copy's bytes must also have
     * gone out.
     */
    fun collectedByFreeUp(
        window: ConfirmWindow?,
        id: Long,
        now: Long,
        evidence: Evidence,
        appDeletedIt: Boolean,
        txSinceRelease: Long?,
        fileBytes: Long
    ): Boolean = heldForReturn(window, id, now, evidence, appDeletedIt) &&
        (txSinceRelease == null || confirmedExact(txSinceRelease, fileBytes))

    // Whether an original may be reclaimed is decided by ReclaimRules.refuse,
    // and whether one of the app's own copies may go by DeletePlanner. Both
    // used to have a second version here that nothing called, and the reclaim
    // one disagreed: it allowed an original the moment proof landed, where the
    // live rule makes every grade wait thirty days. An uncalled rule with its
    // own tests reads like the app's guarantee, so it is not kept here.
}
