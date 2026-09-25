package com.pocketide.limiter

/**
 * The app's size on disk, which only the screens show. Android's own storage accounting
 * ([quick]) answers at once; walking the computer file by file ([walk]) costs seconds of a core
 * and hundreds of thousands of file reads. So the walk is only the fallback for a phone whose
 * Android cannot say, and runs only while someone looks at the app, never in a process woken
 * for a background job.
 */
internal class DataSize(
    private val quick: () -> Long?,
    private val walk: () -> Long,
    private val quickEveryMs: Long,
    private val walkEveryMs: Long,
) {
    private var quickAt: Long? = null
    private var walkAt: Long? = null

    /** The size measured now (blocking), or null when nothing was due or nothing could be measured. */
    @Synchronized
    fun measureIfDue(now: Long, visible: Boolean): Long? {
        val quickEvery = if (visible) quickEveryMs else walkEveryMs
        if (quickAt.isWithin(now, quickEvery)) return null
        quickAt = now
        quick()?.let { return it }
        if (!visible || walkAt.isWithin(now, walkEveryMs)) return null
        walkAt = now
        return walk()
    }

    private fun Long?.isWithin(now: Long, ms: Long): Boolean = this != null && now - this < ms
}
