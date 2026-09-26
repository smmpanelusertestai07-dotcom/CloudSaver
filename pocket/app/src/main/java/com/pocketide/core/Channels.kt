package com.pocketide.core

/** Notification channel ids. Channels are created once at start-up. */
object Channels {
    const val ENGINE = "engine"
    const val SYNC = "sync"
    const val AGENTS = "agents"
    const val BUILDS = "builds"
}

/**
 * Notification ids, all in one place: Android replaces a notification posted with the same id
 * (and tag), so two kinds of notice sharing one would overwrite each other, or the engine's
 * ongoing notification. Notices of one kind that may show side by side (one per room, build or
 * scheduled task) share their kind's id and differ by tag.
 */
object NotificationIds {
    /** The sync worker's "Syncing chats" (a large upload runs in the foreground). */
    const val SYNC_RUNNING = 4100

    /** The engine's ongoing "Computer running" notification (its foreground service). */
    const val ENGINE_RUNNING = 4101
    const val ENGINE_STOPPED = 4102

    /** An agent or computer update running in the foreground (a large download and install). */
    const val UPDATE_RUNNING = 4103

    /** Sync and storage notices: one id per kind, from [SYNC_FIRST] to [SYNC_LAST]. */
    const val SYNC_FIRST = 4200
    const val SYNC_LAST = 4249

    /** The scheduled-task worker's foreground notification. */
    const val SCHEDULED_RUN = 4300

    /** A room's notices, tagged with the agent's id, so they never replace [SCHEDULED_RUN]. */
    const val ROOM = 4300
    const val NEW_AGENTS = 4400
    const val APP_UPDATE = 4500

    /** A build's end, tagged with its run id. */
    const val BUILD_ENDED = 4600

    /** A scheduled task's end, tagged with the task's id. */
    const val SCHEDULED_TASK_ENDED = 4601
}
