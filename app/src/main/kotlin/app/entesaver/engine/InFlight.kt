package app.entesaver.engine

import android.content.Context
import android.os.Process
import androidx.core.content.edit
import app.entesaver.core.logic.ItemState
import app.entesaver.data.db.AppDb
import app.entesaver.util.DeviceTier

/**
 * Which file was being optimised, written down before the work starts.
 *
 * An encode that throws is counted, and three of those set the file aside.
 * An encode that takes the whole app down with it - Android ending a process
 * that ran out of memory - throws nothing, so nothing was counted: the same
 * file came first in the next run, took the app down again, and nothing
 * behind it was ever reached. The note left here survives that, and the next
 * run reads it: the file gets its strike, and a photo that has now taken the
 * app down twice brings this phone's decode ceiling down a step.
 *
 * A note is also left when the app is closed some other way mid-file - a
 * swipe from recents - which is why it takes three strikes, the same as an
 * ordinary failure, before a file is set aside.
 */
object InFlight {

    private const val PREFS = "inflight"
    private const val KEY_ID = "id"
    private const val KEY_PID = "pid"

    const val STRIKES = 3
    const val REASON = "process_died"

    /** Strikes on one photo after which the decode ceiling comes down. */
    const val LOWER_AFTER = 2

    fun begin(context: Context, rowId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(KEY_ID, rowId)
            putInt(KEY_PID, Process.myPid())
        }
    }

    fun end(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }

    /**
     * Reads a note left by a run that never came back. True when there was
     * one. A note this same process wrote is not one: the file is still
     * being optimised - by the trial run on Home, say - and has done nothing
     * wrong.
     */
    suspend fun recover(context: Context, db: AppDb): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return false
        if (prefs.getInt(KEY_PID, -1) == Process.myPid()) return false
        end(context)
        val row = db.items().byId(id) ?: return true
        if (row.state != ItemState.NEW.name) return true
        val strikes = row.attempts + 1
        if (!row.isVideo && strikes >= LOWER_AFTER) DeviceTier.lowerCeiling(context)
        db.items().update(
            row.copy(
                attempts = strikes,
                lastError = REASON,
                state = if (strikes >= STRIKES) ItemState.SKIP.name else row.state,
                skipReason = if (strikes >= STRIKES) REASON else row.skipReason,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    // ---- light copies remade during Free up -----------------------------

    /*
     * Free up remakes a light copy from the original when nothing provable is
     * left on the phone, while its screen waits. A row there is long past NEW,
     * so the note above would be read and dropped without a strike, and a
     * remake that took the app down did it again on every retry. This note
     * is its own, in its own file, so the background pass clearing its note
     * can never clear this one mid-remake.
     */
    private const val REMAKE_PREFS = "inflight_remake"
    private const val KEY_VIDEO = "video"
    private const val KEY_STRUCK_ID = "struck_id"
    private const val KEY_STRUCK_N = "struck_n"

    fun beginRemake(context: Context, rowId: Long, isVideo: Boolean) {
        context.getSharedPreferences(REMAKE_PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(KEY_ID, rowId)
            putInt(KEY_PID, Process.myPid())
            putBoolean(KEY_VIDEO, isVideo)
        }
    }

    /** The remake of [rowId] came back, so a strike against it is spent. */
    fun endRemake(context: Context, rowId: Long) {
        val prefs = context.getSharedPreferences(REMAKE_PREFS, Context.MODE_PRIVATE)
        val cleared = prefs.getLong(KEY_STRUCK_ID, -1) == rowId
        prefs.edit {
            remove(KEY_ID)
            remove(KEY_PID)
            remove(KEY_VIDEO)
            if (cleared) {
                remove(KEY_STRUCK_ID)
                remove(KEY_STRUCK_N)
            }
        }
    }

    /**
     * The row whose remake a run that never came back was making, or null.
     * The caller leaves that row out of the batch it is starting, so a person
     * retrying by hand is not taken down by the same file again. A photo that
     * has done it twice brings the decode ceiling down a step, the same rule
     * as the background pass: once may have been a swipe from recents. Read
     * before a batch starts, because its first remake writes a note of its own.
     */
    fun recoverRemake(context: Context): Long? {
        val prefs = context.getSharedPreferences(REMAKE_PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1)
        if (id < 0) return null
        if (prefs.getInt(KEY_PID, -1) == Process.myPid()) return null
        val video = prefs.getBoolean(KEY_VIDEO, false)
        val strikes = if (prefs.getLong(KEY_STRUCK_ID, -1) == id) prefs.getInt(KEY_STRUCK_N, 0) + 1 else 1
        prefs.edit {
            remove(KEY_ID)
            remove(KEY_PID)
            remove(KEY_VIDEO)
            putLong(KEY_STRUCK_ID, id)
            putInt(KEY_STRUCK_N, strikes)
        }
        if (!video && strikes >= LOWER_AFTER) DeviceTier.lowerCeiling(context)
        return id
    }
}
