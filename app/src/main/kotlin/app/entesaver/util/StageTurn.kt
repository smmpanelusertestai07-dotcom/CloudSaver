package app.entesaver.util

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Free up's turn at the encoder ([Locks.stage]), taken at most once for a
 * whole batch.
 *
 * A remake used to take the lock row by row and wait for it with no limit.
 * Behind a background run that kept encoding, every remade row waited for
 * one more file - a clip can take twenty minutes - and the screen sat on its
 * spinner for as long. Now the batch asks once, at its first remake, and the
 * background run starts no new file while it waits ([Locks.runShouldYield]).
 * The wait is [waitMs] at most, for the file already under way, and
 * [onWaiting] lets the screen say so while it lasts. Once held, the turn is kept for the
 * rest of the batch, so the rows never take turns with the run.
 *
 * A file that outlasts the wait leaves the batch's remakes out, each named
 * ([refusals] counts them), their originals kept. The run then starts no new
 * file for [yieldMs] - as long as one file can take - so a second try a few
 * minutes later finds the encoder free. After that, or once a batch has had
 * its turn, the run carries on.
 *
 * Used by one batch, from one coroutine; [close] goes in a finally.
 */
class StageTurn(
    private val waitMs: Long,
    private val yieldMs: Long,
    private val onWaiting: (Boolean) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var asked = false
    private var held = false

    /** Remakes this batch left out because the encoder was busy. */
    var refusals = 0
        private set

    /** True once this batch holds [Locks.stage]; false when the remake must wait for another try. */
    suspend fun take(): Boolean {
        if (held) return true
        if (asked) {
            refusals++
            return false
        }
        asked = true
        Locks.freeUpWaiting.incrementAndGet()
        held = Locks.stage.tryLock() || run {
            onWaiting(true)
            try {
                withTimeoutOrNull(waitMs) { Locks.stage.lock() } != null
            } finally {
                onWaiting(false)
            }
        }
        if (!held) refusals++
        return held
    }

    /** Lets go of the encoder, and keeps the run waiting a while if this batch could not have it. */
    fun close() {
        if (!asked) return
        if (held) {
            held = false
            Locks.stage.unlock()
            // The remakes it was keeping the run waiting for are done.
            Locks.runYieldsUntil.set(0L)
        } else if (refusals > 0) {
            // A batch that left the screen mid-wait refused nothing, and
            // holds the run up no longer.
            val until = clock() + yieldMs
            Locks.runYieldsUntil.accumulateAndGet(until) { a, b -> maxOf(a, b) }
        }
        Locks.freeUpWaiting.decrementAndGet()
        asked = false
    }
}
