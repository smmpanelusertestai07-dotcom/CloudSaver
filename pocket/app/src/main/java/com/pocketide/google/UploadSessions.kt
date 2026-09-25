package com.pocketide.google

import com.pocketide.core.Clock

/**
 * Open resumable upload sessions, so an upload cut off by a lost connection continues where
 * it stopped on the next try instead of starting again. Memory only: a session address lets
 * anyone holding it add bytes, so it never goes to disk. Drive keeps a session for a week;
 * this forgets it sooner.
 */
internal class UploadSessions(private val clock: Clock = Clock.SYSTEM) {
    private data class Open(val uri: String, val openedAt: Long)

    private val open = LinkedHashMap<String, Open>()

    @Synchronized
    fun get(key: String): String? {
        val found = open[key] ?: return null
        if (clock.now() - found.openedAt > MAX_AGE_MS) {
            open.remove(key)
            return null
        }
        return found.uri
    }

    @Synchronized
    fun put(key: String, uri: String) {
        open.remove(key)
        open[key] = Open(uri, clock.now())
        while (open.size > MAX_OPEN) open.remove(open.keys.first())
    }

    @Synchronized
    fun remove(key: String) {
        open.remove(key)
    }

    companion object {
        private const val MAX_AGE_MS = 5L * 24 * 60 * 60 * 1000
        private const val MAX_OPEN = 32

        /** One key per account, target, name and exact content. */
        fun key(account: String?, name: String, existingId: String?, md5: String, length: Long): String =
            listOf(account.orEmpty(), existingId.orEmpty(), name, md5, length.toString()).joinToString("\u0000")
    }
}
