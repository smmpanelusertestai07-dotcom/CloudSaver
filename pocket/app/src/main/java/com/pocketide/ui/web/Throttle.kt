package com.pocketide.ui.web

/** Lets something through at most once every [everyMs]; the first call always passes. Main thread only. */
class Throttle(private val everyMs: Long, private val now: () -> Long) {
    private var last: Long? = null

    fun ready(): Boolean {
        val time = now()
        val previous = last
        if (previous != null && time - previous < everyMs) return false
        last = time
        return true
    }
}
