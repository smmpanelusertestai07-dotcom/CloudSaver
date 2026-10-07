package app.entesaver.core.logic

/**
 * When the Home card about an earlier install's files may go for good.
 *
 * Android's delete dialog answers with the files it removed, and a Cancel
 * answers with none. The card used to count any answer as done, so one
 * Cancel hid it for ever while the files kept taking space. Only "Keep", or
 * every file actually gone, ends it; anything less asks again later about
 * the files still there.
 */
object LeftoverRules {

    /** Whether every file in [asked] is among those Android removed ([deleted]). */
    fun <T> allRemoved(asked: Collection<T>, deleted: Collection<T>): Boolean {
        val gone = deleted.toHashSet()
        return asked.all { it in gone }
    }
}
