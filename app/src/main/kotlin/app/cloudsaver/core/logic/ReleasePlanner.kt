package app.cloudsaver.core.logic

/**
 * Daily release: pick staged files (newest capture first) whose sizes fit into the
 * daily cap. Always releases at least one file when anything is staged - the daily
 * release IS the network cap, and the output folder must never stay empty.
 */
object ReleasePlanner {

    data class Staged(val id: Long, val bytes: Long, val captureAt: Long)

    fun plan(staged: List<Staged>, capBytes: Long): List<Long> {
        if (staged.isEmpty()) return emptyList()
        val newestFirst = staged.sortedWith(
            compareByDescending<Staged> { it.captureAt }.thenByDescending { it.id }
        )
        if (capBytes < 0) return newestFirst.map { it.id } // unlimited
        val picked = mutableListOf<Long>()
        var sum = 0L
        for (s in newestFirst) {
            if (picked.isEmpty() || sum + s.bytes <= capBytes) {
                picked += s.id
                sum += s.bytes
            }
            if (sum >= capBytes) break
        }
        return picked
    }
}
