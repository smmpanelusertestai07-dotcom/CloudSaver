package com.pocketide

/**
 * What runs once per process start: notification channels, the periodic jobs (sync, daily
 * maintenance, discovery, update checks) and the access checks. Kept apart from [PocketApp] so
 * integration owns it in one place.
 */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        // Wired during integration.
        graph.hashCode()
    }
}
