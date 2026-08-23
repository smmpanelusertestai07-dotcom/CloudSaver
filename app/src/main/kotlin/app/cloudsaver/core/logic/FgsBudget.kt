package app.cloudsaver.core.logic

import kotlin.math.max
import kotlin.math.min

/**
 * Foreground-service time budget: Android 15/16 limit dataSync/mediaProcessing to
 * 6 h per rolling 24 h; CloudSaver stops at 5.5 h to stay clear of the hard limit.
 * Sessions are (startMs, endMs) pairs persisted as "start:end;start:end".
 */
object FgsBudget {

    fun usedInWindow(
        sessions: List<Pair<Long, Long>>,
        now: Long,
        windowMs: Long = Defaults.FGS_WINDOW_MS
    ): Long {
        val windowStart = now - windowMs
        var used = 0L
        for ((s, e) in sessions) {
            val start = max(s, windowStart)
            val end = min(e, now)
            if (end > start) used += end - start
        }
        return used
    }

    fun remaining(
        sessions: List<Pair<Long, Long>>,
        now: Long,
        capMs: Long = Defaults.FGS_BUDGET_MS,
        windowMs: Long = Defaults.FGS_WINDOW_MS
    ): Long = max(0L, capMs - usedInWindow(sessions, now, windowMs))

    fun prune(
        sessions: List<Pair<Long, Long>>,
        now: Long,
        windowMs: Long = Defaults.FGS_WINDOW_MS
    ): List<Pair<Long, Long>> =
        sessions.filter { it.second >= now - windowMs }.takeLast(64)

    fun encode(sessions: List<Pair<Long, Long>>): String =
        sessions.joinToString(";") { "${it.first}:${it.second}" }

    fun decode(encoded: String): List<Pair<Long, Long>> =
        encoded.split(';').mapNotNull { part ->
            val bits = part.split(':')
            if (bits.size != 2) return@mapNotNull null
            val s = bits[0].toLongOrNull() ?: return@mapNotNull null
            val e = bits[1].toLongOrNull() ?: return@mapNotNull null
            if (e >= s) s to e else null
        }
}
