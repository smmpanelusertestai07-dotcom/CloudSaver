package com.pocketide.limiter

/**
 * How long the engine may still hold the CPU. One stretch of work gets at most [maxMs] of wake
 * lock; the stretch ends only once nothing has worked for [quietMs], so work that pauses for a
 * minute (a file watcher, a dev server's ticks) cannot restart the clock and keep the phone
 * awake all night. Kept outside the service's collector, which restarts on every change.
 */
internal class WakeBudget(private val maxMs: Long, private val quietMs: Long) {
    private var since: Long? = null
    private var restingSince: Long? = null

    @Synchronized
    fun update(now: Long, working: Boolean) {
        if (working) {
            val rest = restingSince
            if (since == null || (rest != null && now - rest >= quietMs)) since = now
            restingSince = null
        } else if (restingSince == null) {
            restingSince = now
        }
    }

    /** Wake lock left in this stretch from [now]; 0 while nothing works or once the stretch used it all. */
    @Synchronized
    fun remainingMs(now: Long): Long {
        val start = since ?: return 0
        if (restingSince != null) return 0
        return (start + maxMs - now).coerceAtLeast(0)
    }
}
