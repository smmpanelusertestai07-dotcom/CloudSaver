package app.entesaver.util

import android.os.SystemClock

/**
 * A trip the app itself sends the person on - the phone's Settings, the
 * gallery viewer, a share sheet - and expects them straight back from.
 *
 * The app lock re-arms whenever the app leaves the foreground, which is
 * right for a phone put down and picked up by someone else, and wrong for
 * the tap the app itself asked for: "Open" on the Permissions screen, grant
 * the switch, come back - and be met by a fingerprint prompt for an errand
 * the app sent you on. A lock that interrupts the person's own action is a
 * lock they turn off. So every place the app starts an outside activity
 * says so here first, and the lock lets that one return through, for as
 * long as an errand reasonably takes. Longer than that - the phone put down
 * on the Settings page for an hour - and it locks as it always did, because
 * a lock with an open-ended grace period is a lock anyone can walk past.
 *
 * Times are the monotonic clock, so a changed wall clock cannot stretch the
 * window.
 */
object Errand {

    /** How long a trip may take before coming back counts as coming back to a locked app. */
    const val GRACE_MS = 120_000L

    @Volatile private var expectingUntil = 0L
    @Volatile private var leftAt = 0L

    /** The app is about to send the person out and expects them back. */
    fun begin(now: Long = SystemClock.elapsedRealtime()) {
        expectingUntil = now + GRACE_MS
    }

    /**
     * The trip never started: the page refused to open. Without this a
     * failed start left the grace armed, and the next time the person left
     * the app by themselves - within two minutes - the lock let them back in.
     */
    fun cancel() {
        expectingUntil = 0L
    }

    /**
     * The app is in front again without ever having left: the trip was a
     * dialog over it (Android's battery question, a chooser) and is over.
     * Left armed, the next time the person pressed Home on their own - within
     * two minutes - the lock let them back in.
     */
    fun resumed() {
        if (leftAt == 0L) expectingUntil = 0L
    }

    /** True while a trip the app started is still the likely reason for leaving. */
    fun expecting(now: Long = SystemClock.elapsedRealtime()): Boolean = now < expectingUntil

    /** No screen is in front, and the app sent the person out itself. */
    fun left(now: Long = SystemClock.elapsedRealtime()) {
        leftAt = now
    }

    /**
     * A screen is in front again. True when the trip took longer than the
     * grace and the app must lock; the trip is over either way.
     */
    fun returnedNeedsLock(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val left = leftAt
        leftAt = 0L
        expectingUntil = 0L
        return left > 0L && now - left > GRACE_MS
    }

    /** Tests only: back to the state at process start. */
    fun reset() {
        expectingUntil = 0L
        leftAt = 0L
    }
}
