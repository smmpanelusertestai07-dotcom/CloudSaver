package app.cloudsaver.core.logic

/**
 * When a tab is allowed to wear a dot.
 *
 * A badge is a claim on attention, so the rules are kept in one place and
 * tested. Two rules only: something is wrong, or there is enough space to
 * reclaim that it is worth a trip. Anything else - a finished backup, a new
 * log line, a routine scan - is news the app already shows on the screen it
 * belongs to, and does not get to interrupt.
 */
object TabBadges {

    /**
     * Below this the trip is not worth the tap. Decimal, because every other
     * size in the app is decimal and a "1 GB" threshold should mean the same
     * number the Storage screen prints.
     */
    const val RECLAIMABLE_DOT_BYTES = 1_000_000_000L

    /** Storage: only when there is more than a gigabyte actually reclaimable. */
    fun storage(reclaimableBytes: Long): Boolean =
        reclaimableBytes > RECLAIMABLE_DOT_BYTES

    /**
     * Settings: only for problems the user can fix. Paused is deliberate and
     * self-evident on Home, so it is not a problem; a missing cloud app,
     * blocked background work, revoked usage access and low space are.
     */
    fun settings(
        cloudMissing: Boolean,
        usageAccessOff: Boolean,
        backgroundWorkStopped: Boolean,
        spaceLow: Boolean
    ): Boolean = cloudMissing || usageAccessOff || backgroundWorkStopped || spaceLow
}
