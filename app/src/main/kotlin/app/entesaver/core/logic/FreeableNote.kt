package app.entesaver.core.logic

/**
 * When to tell the person that space can be freed.
 *
 * Freeing space is why most people install the app, and the moment it
 * becomes possible arrives weeks after setup, when nobody is looking at the
 * app. The mark on the Storage tab says it only to someone who opens it. So
 * it is said once, as a notification, when more than the same gigabyte is
 * ready - and again only when that has doubled, or after the person has
 * freed it and it builds up again. Never more: an app that keeps announcing
 * what it could do is an app whose notifications get switched off.
 */
object FreeableNote {

    /** The gigabyte the Storage tab marks (TabBadges). */
    const val THRESHOLD_BYTES = TabBadges.RECLAIMABLE_DOT_BYTES

    /** Whether to say so now, given what was said last ([lastSaid], 0 when nothing). */
    fun due(freeable: Long, lastSaid: Long): Boolean =
        freeable > THRESHOLD_BYTES && freeable >= 2 * lastSaid

    /** What to keep remembering: forgotten once the person has freed it. */
    fun remembered(freeable: Long, lastSaid: Long): Long =
        if (freeable <= THRESHOLD_BYTES) 0L else lastSaid
}
