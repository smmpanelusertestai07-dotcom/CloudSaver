package com.pocketide.sessions

import com.pocketide.core.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Pushes a session's branch at most once every [intervalMs]. A call inside the window answers with
 * the last result and makes sure one more push happens when the window ends, so commits made in
 * between still reach GitHub without a push per commit.
 */
internal class Autosaver(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val intervalMs: Long = INTERVAL_MS,
    private val push: suspend (sessionId: String) -> String?,
) {
    private class Window {
        val lock = Mutex()
        var lastAt: Long? = null
        var lastResult: String? = null
        var followUp: Job? = null
    }

    private val windows = ConcurrentHashMap<String, Window>()

    suspend fun save(sessionId: String): String? {
        val window = windows.computeIfAbsent(sessionId) { Window() }
        return window.lock.withLock {
            val now = clock.now()
            val last = window.lastAt
            if (last != null && now - last < intervalMs) {
                followUp(sessionId, window, last + intervalMs - now)
                window.lastResult
            } else {
                window.lastAt = now
                push(sessionId).also { window.lastResult = it }
            }
        }
    }

    /** Stops tracking a session that no longer exists here. */
    fun forget(sessionId: String) {
        windows.remove(sessionId)?.followUp?.cancel()
    }

    private fun followUp(sessionId: String, window: Window, waitMs: Long) {
        if (window.followUp?.isActive == true) return
        window.followUp = scope.launch {
            delay(waitMs)
            try {
                save(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                // The next autosave call tries again.
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 5 * 60 * 1000L
    }
}
