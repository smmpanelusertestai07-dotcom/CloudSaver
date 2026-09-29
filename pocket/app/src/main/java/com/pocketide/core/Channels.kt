package com.pocketide.core

/** Notification channel ids. Channels are created once at start-up. */
object Channels {
    /** The ongoing "PocketIDE's computer is on" notice with its Stop button. */
    const val COMPUTER = "computer"

    /** A sign-in page a program asked to open while PocketIDE was not in front. */
    const val LINKS = "links"
}

/**
 * Notification ids, all in one place: Android replaces a notification posted with the same id,
 * so two kinds of notice sharing one would overwrite each other.
 */
object NotificationIds {
    /** The computer service's ongoing notice (its foreground notification). */
    const val COMPUTER_ON = 4101

    /** The latest link a program asked to open. */
    const val LINK = 4103
}
