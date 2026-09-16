package app.cloudsaver.core.logic

/**
 * When the phone has stopped CloudSaver's background work, and when to say so
 * in a notification rather than only on Home.
 *
 * The in-app chip is passive: it shows when the app is opened, and the app
 * is one nobody opens for weeks. So a phone that has stopped the work gets
 * one notification - not one a day, which is the cadence every other alert
 * here keeps and would be nagging for a state the person may have chosen -
 * and after three of them the app stops asking and leaves it to the chip.
 * That is the cadence backup apps people already keep have settled on: say
 * it, space it a week apart, and stop.
 *
 * The alert can only be posted by a run that does happen, so a phone that
 * has stopped every run says nothing here; that limit is honest and stated
 * on the Permissions screen. What this catches is the common shape: runs
 * that come rarely, or that Android cuts short.
 */
object StallAlert {

    /** Nothing has run for this long while work waited: the phone stopped it. */
    const val STALL_MS = 48 * 3_600_000L

    /** A week between reminders, not a day. */
    const val SPACING_MS = 7 * 86_400_000L

    /** After this many, the chip on Home is the only notice. */
    const val MAX_ALERTS = 3

    /** The same evidence Home's "stopped" chip uses, in one place. */
    fun stalled(now: Long, lastRunAt: Long, waiting: Int, rationed: Boolean): Boolean =
        waiting > 0 && lastRunAt > 0 && (now - lastRunAt > STALL_MS || rationed)

    /** Whether a notification is owed right now for a stall. */
    fun due(stalled: Boolean, sentCount: Int, lastSentAt: Long, now: Long): Boolean =
        stalled && sentCount < MAX_ALERTS && (lastSentAt <= 0L || now - lastSentAt >= SPACING_MS)
}
