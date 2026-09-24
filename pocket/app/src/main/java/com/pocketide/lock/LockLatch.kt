package com.pocketide.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app lock's state, without Android: locked from the moment the process starts, unlocked
 * only by a passed prompt, locked again after [graceMs] in the background ([errandMs] when the
 * app itself sent the owner out). With the app lock off in Settings it is always unlocked.
 * [elapsed] is a monotonic clock (elapsedRealtime), so changing the phone's time cannot skip it.
 */
internal class LockLatch(
    enabled: Boolean,
    private val elapsed: () -> Long,
    private val graceMs: Long = GRACE_MS,
    private val errandMs: Long = ERRAND_MS,
) {
    private var enabled = enabled
    private var locked = true
    private var leftAt: Long? = null
    private var errandAt: Long? = null
    private val flow = MutableStateFlow(!enabled)
    val unlocked: StateFlow<Boolean> = flow

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        // Turning the lock on while using the app does not lock at once; the next absence does.
        if (on) locked = false
        publish()
    }

    @Synchronized
    fun lockNow() {
        locked = true
        publish()
    }

    @Synchronized
    fun passed() {
        locked = false
        leftAt = null
        errandAt = null
        publish()
    }

    @Synchronized
    fun onBackground() {
        if (leftAt == null) leftAt = elapsed()
    }

    /** The app is about to send the owner out; only an absence that starts right after counts as the errand. */
    @Synchronized
    fun onErrand() {
        errandAt = elapsed()
    }

    @Synchronized
    fun onForeground() {
        val left = leftAt ?: return
        val errand = errandAt?.let { left - it in 0..ERRAND_START_MS } == true
        if (elapsed() - left >= if (errand) errandMs else graceMs) locked = true
        leftAt = null
        errandAt = null
        publish()
    }

    private fun publish() {
        flow.value = !enabled || !locked
    }

    companion object {
        const val GRACE_MS = 30_000L
        const val ERRAND_MS = 3 * 60_000L

        /** How soon after [onErrand] the app must leave for the absence to be that errand. */
        const val ERRAND_START_MS = 10_000L
    }
}
