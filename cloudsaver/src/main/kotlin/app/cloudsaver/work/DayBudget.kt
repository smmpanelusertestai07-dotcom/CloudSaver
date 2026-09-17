package app.cloudsaver.work

import android.content.Context
import app.cloudsaver.core.logic.RunDecider
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.DayBudgetRow
import app.cloudsaver.util.Formats

/**
 * Reads and updates today's on-battery budget row. The key is the local date,
 * so the budget resets by itself at local midnight.
 */
class DayBudget(context: Context) {

    private val db = AppDb.get(context)

    suspend fun read(now: Long): RunDecider.Budget {
        val day = Formats.dayKey(now)
        val row = db.dayBudget().byDay(day)
        return RunDecider.Budget(
            videoEncodeMs = row?.videoEncodeMs ?: 0L,
            photosOnBattery = row?.photosOnBattery ?: 0
        )
    }

    suspend fun addVideoEncode(now: Long, ms: Long) {
        if (ms <= 0) return
        val day = ensureRow(now)
        db.dayBudget().addEncodeMs(day, ms)
    }

    suspend fun addPhotos(now: Long, count: Int) {
        if (count <= 0) return
        val day = ensureRow(now)
        db.dayBudget().addPhotos(day, count)
    }

    private suspend fun ensureRow(now: Long): String {
        val day = Formats.dayKey(now)
        db.dayBudget().insert(DayBudgetRow(day))
        db.dayBudget().pruneOlderThan(day)
        return day
    }
}
