package app.cloudsaver.core.logic

import kotlin.math.ceil

/**
 * Turns the daily upload allowance into hourly slices, and decides how many
 * copies may be in flight at once.
 *
 * Releasing a whole day's worth in one burst makes per-file proof impossible:
 * once ten files leave together, the cloud app's byte counter cannot say which
 * of them arrived. Sending them a few at a time, and often only one, is what
 * turns a byte count into evidence about a specific file.
 */
object Pacing {

    /** Slices per day. Twelve gives roughly two-hourly releases. */
    const val SLICES_PER_DAY = 12

    /** A copy stops waiting for per-file proof after this long. */
    const val IN_FLIGHT_TIMEOUT_MS = 6 * 3_600_000L

    /** One at a time unless the cloud tells us when a file has landed. */
    const val IN_FLIGHT_WITHOUT_ORACLE = 1
    const val IN_FLIGHT_WITH_ORACLE = 4

    /**
     * Bytes this maintenance pass may release.
     *
     * Always at least one file's worth: a cap smaller than the next file must
     * not stall the pipeline forever, and the anchor rule needs the folder to
     * stay populated.
     */
    fun sliceBytes(dailyCapBytes: Long): Long {
        if (dailyCapBytes < 0) return -1L // unlimited
        if (dailyCapBytes == 0L) return 0L
        return ceil(dailyCapBytes.toDouble() / SLICES_PER_DAY).toLong().coerceAtLeast(1L)
    }

    /** What is left of today's allowance after [releasedToday]. */
    fun remainingToday(dailyCapBytes: Long, releasedToday: Long): Long {
        if (dailyCapBytes < 0) return -1L
        return (dailyCapBytes - releasedToday).coerceAtLeast(0L)
    }

    /** Bytes this pass may actually send: the slice, capped by what is left. */
    fun allowanceNow(dailyCapBytes: Long, releasedToday: Long): Long {
        if (dailyCapBytes < 0) return -1L
        val left = remainingToday(dailyCapBytes, releasedToday)
        if (left <= 0) return 0L
        return minOf(sliceBytes(dailyCapBytes), left)
    }

    /**
     * At most this many days of unused allowance may be carried forward.
     *
     * A phone that was off for a week should not then push a week of uploads
     * in one afternoon - that is exactly the mobile-data bill the cap exists
     * to prevent. One extra day is enough to absorb a normal gap.
     */
    const val MAX_CATCH_UP_DAYS = 1

    /**
     * Today's allowance, including anything carried over from a day the app
     * could not use.
     */
    fun dailyBudgetWithCatchUp(dailyCapBytes: Long, carriedBytes: Long): Long {
        if (dailyCapBytes < 0) return -1L
        val maxCarry = dailyCapBytes * MAX_CATCH_UP_DAYS
        return dailyCapBytes + carriedBytes.coerceIn(0L, maxCarry)
    }

    /**
     * What yesterday left behind, given how much of its budget went unused.
     * Anything beyond the carry limit is simply forgotten.
     */
    fun carryForward(dailyCapBytes: Long, releasedYesterday: Long): Long {
        if (dailyCapBytes < 0) return 0L
        val unused = (dailyCapBytes - releasedYesterday).coerceAtLeast(0L)
        return unused.coerceAtMost(dailyCapBytes * MAX_CATCH_UP_DAYS)
    }

    /**
     * A queue longer than this is a backlog, not a steady state.
     *
     * Pacing buys per-file proof and it costs throughput: one copy at a time
     * is a handful of files a day. That trade is right once someone is caught
     * up - every new photo provable, and eventually reclaimable - and badly
     * wrong for a ten-year gallery that has not been through once yet. So a
     * backlog goes out at full slice speed on batch evidence, and pacing
     * takes over when the queue is short enough for it to be worth having.
     */
    const val BACKLOG_BURST_ITEMS = 20

    /**
     * How many copies this pass may release, or null for "no per-item limit"
     * (the byte slice still applies).
     *
     * Per-file proof needs the cloud app's byte counter, so where the user has
     * not granted Usage Access there is nothing to be gained by holding files
     * back at all.
     */
    fun releaseSlots(
        slotsFree: Int,
        stagedWaiting: Int,
        perFileProofPossible: Boolean
    ): Int? = when {
        !perFileProofPossible -> null
        stagedWaiting > BACKLOG_BURST_ITEMS -> null
        else -> slotsFree
    }

    fun inFlightLimit(cloudHasFreeUpOracle: Boolean): Int =
        if (cloudHasFreeUpOracle) IN_FLIGHT_WITH_ORACLE else IN_FLIGHT_WITHOUT_ORACLE

    /** A copy released this long ago has stopped being usable as paced proof. */
    fun isTimedOut(releasedAt: Long, now: Long): Boolean =
        now - releasedAt >= IN_FLIGHT_TIMEOUT_MS

    /**
     * How many more copies may go out now: the limit, minus those still
     * waiting, ignoring any that have already timed out.
     */
    fun slotsFree(
        inFlightReleasedAt: List<Long>,
        now: Long,
        cloudHasFreeUpOracle: Boolean
    ): Int {
        val waiting = inFlightReleasedAt.count { !isTimedOut(it, now) }
        return (inFlightLimit(cloudHasFreeUpOracle) - waiting).coerceAtLeast(0)
    }
}
