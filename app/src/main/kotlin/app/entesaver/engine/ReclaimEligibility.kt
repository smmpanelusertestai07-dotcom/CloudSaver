package app.entesaver.engine

import android.content.Context
import app.entesaver.core.logic.Evidence
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.ReclaimRules
import app.entesaver.data.EnteApp
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.data.prefs.Options
import app.entesaver.media.MediaScanner
import app.entesaver.util.Formats

/**
 * The one place that answers "which originals may be offered for removal".
 *
 * Two screens used to answer it separately. The Free up space hub summed
 * `reclaimCandidates()` raw - every original whose copy has any evidence at
 * all - and printed the total as "you could free about 6.2 GB". The Reclaim
 * screen it links to took the same rows and put each one through
 * [ReclaimRules.isEligible], which refuses anything under thirty days settled,
 * anything while the cloud app is missing or flagged, anything too small, and
 * anything the user marked a favourite. So the hub advertised a figure the
 * destination refused in full: the headline said gigabytes and the list opened
 * empty, most visibly for anyone in their first month of use, which is
 * everyone at first.
 *
 * Room's own comment records that this exact class of bug was already fixed
 * once for `reclaimableBytesFlow` - and fixed by aligning that query with
 * `reclaimCandidates`, which is the wrong end of the asymmetry. Both callers
 * now go through here, so there is nothing left to drift.
 *
 * The two per-screen switches keep their Reclaim defaults, because those are
 * what a user who has never touched them will meet.
 */
object ReclaimEligibility {

    /** Reclaim's own defaults, so the hub promises what that screen offers. */
    const val SKIP_FAVOURITES_DEFAULT = true
    const val SKIP_SMALL_DEFAULT = true

    /** A row and the candidate the rules judged, kept together. */
    data class Judged(val row: ItemRow, val candidate: ReclaimRules.Candidate)

    fun candidateOf(
        row: ItemRow,
        now: Long,
        inLedger: Boolean,
        favourite: Boolean = false
    ) = ReclaimRules.Candidate(
        id = row.id,
        fingerprint = row.fingerprint,
        sizeBytes = row.sizeBytes,
        optimisedBytes = row.outputBytes ?: 0L,
        evidence = Evidence.parse(row.evidence),
        confirmedAgeDays = Formats.daysBetween(row.confirmedAt ?: row.releasedAt ?: now, now),
        state = runCatching { ItemState.valueOf(row.state) }.getOrDefault(ItemState.UNKNOWN),
        hasLedgerEntry = inLedger,
        // The ledger is keyed by the copy's hash, so finding the row at all is
        // the hash check: a changed copy would hash to something else.
        ledgerHashMatches = inLedger,
        originalPresent = !row.originalMissing,
        inExcludedAlbum = false,
        // Read from the gallery's own star. It was a constant false, so the
        // rule that skips favourites - on by default - skipped nothing.
        isFavourite = favourite,
        addedDaysAgo = Formats.daysBetween(row.dateAdded * 1000, now),
        isVideo = row.isVideo,
        album = row.bucket,
        capturedAtMs = row.captureAt
    )

    /**
     * Whether a row is a gallery favourite. When the gallery could not be
     * asked ([favourites] null), every row counts as one: the screen then
     * offers nothing this time rather than an original that may be starred.
     */
    fun isFavourite(row: ItemRow, favourites: Set<String>?): Boolean =
        favourites == null || (row.contentUri != null && row.contentUri in favourites)

    /** True when Ente is installed and nothing is flagged against it. */
    fun cloudHealthy(ctx: Context, o: Options): Boolean {
        if (o.cloudProblem.isNotEmpty()) return false
        return EnteApp.isInstalled(ctx)
    }

    /**
     * Every original the app may currently offer for removal, judged.
     *
     * Whatever this returns, the hub may add up and the Reclaim screen may
     * list - they are the same rows.
     */
    suspend fun judged(
        ctx: Context,
        db: AppDb,
        o: Options,
        now: Long,
        skipFavourites: Boolean = SKIP_FAVOURITES_DEFAULT,
        skipSmall: Boolean = SKIP_SMALL_DEFAULT
    ): List<Judged> {
        val healthy = cloudHealthy(ctx, o)
        val ledger = db.ledger().all().mapTo(HashSet()) { it.outputSha256 }
        val favourites = if (skipFavourites) MediaScanner(ctx, db).favouriteUris() else emptySet()
        return db.items().reclaimCandidates().mapNotNull { row ->
            val candidate = candidateOf(
                row, now, ledger.contains(row.outputSha256),
                favourite = isFavourite(row, favourites)
            )
            if (ReclaimRules.isEligible(
                    candidate, healthy,
                    // The user's answer, not a constant. A day's byte total
                    // says a day's photographs went out, not that this one
                    // did, and the rule has always said that counts only
                    // behind an explicit opt-in. Hard-coding true here meant
                    // the screen offered those originals with nobody having
                    // opted in to anything.
                    allowVerifiedBySize = o.freeUpAllowVerified30,
                    skipFavourites = skipFavourites, skipSmall = skipSmall
                )
            ) {
                Judged(row, candidate)
            } else {
                null
            }
        }
    }
}
