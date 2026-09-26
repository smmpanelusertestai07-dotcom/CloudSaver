package com.pocketide.core

/** Notification channel ids. Channels are created once at start-up. */
object Channels {
    /** The ongoing "Cloud computer is on" notice with its Stop button. */
    const val COMPUTER = "computer"
}

/**
 * Notification ids, all in one place: Android replaces a notification posted with the same id,
 * so two kinds of notice sharing one would overwrite each other.
 */
object NotificationIds {
    /** The connection service's ongoing notice (its foreground notification). */
    const val COMPUTER_ON = 4101

    /** "Your cloud computer stopped", posted when the service ends because GitHub stopped it. */
    const val COMPUTER_STOPPED = 4102
}
