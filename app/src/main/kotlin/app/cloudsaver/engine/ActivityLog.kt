package app.cloudsaver.engine

import android.content.Context
import app.cloudsaver.data.db.ActivityRow
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.util.Formats

/**
 * The app's own record of what it did, in the user's words.
 *
 * Background work is invisible by nature, and a notification is gone the
 * moment it is swiped. Everything worth telling the user lands here as well,
 * so "what has this thing actually been doing" always has an answer.
 */
class ActivityLog(private val context: Context) {

    private val db = AppDb.get(context)

    /** Event kinds. Stored as names so the log survives schema changes. */
    enum class Kind {
        OPTIMISED, RELEASED, BACKED_UP, PAUSED, RESUMED,
        CLOUD_PROBLEM, SKIPPED, RECLAIMED, SETTINGS_CHANGED, RECOVERED,

        /** Something the app could not do, that the user needs to know. */
        PROBLEM;

        /** The three filters the screen offers, plus All. */
        val group: Group
            get() = when (this) {
                OPTIMISED, RELEASED, BACKED_UP, RECLAIMED -> Group.BACKUPS
                PAUSED, CLOUD_PROBLEM, SKIPPED, PROBLEM -> Group.PROBLEMS
                RESUMED, SETTINGS_CHANGED, RECOVERED -> Group.CHANGES
            }
    }

    enum class Group { BACKUPS, PROBLEMS, CHANGES }

    /** Rows kept: whichever of these bites first. */
    companion object {
        const val RETENTION_DAYS = 30
        const val RETENTION_ROWS = 500
    }

    suspend fun record(
        kind: Kind,
        detail: String? = null,
        count: Int = 0,
        bytes: Long = 0,
        filterState: String? = null,
        atMs: Long = System.currentTimeMillis()
    ) {
        runCatching {
            db.activity().insert(
                ActivityRow(
                    atMs = atMs,
                    kind = kind.name,
                    detail = detail,
                    count = count,
                    bytes = bytes,
                    filterState = filterState
                )
            )
            prune(atMs)
        }
    }

    /** Only worth a line when something actually happened. */
    suspend fun recordIfAny(kind: Kind, count: Int, bytes: Long = 0, detail: String? = null) {
        if (count > 0) record(kind, detail = detail, count = count, bytes = bytes)
    }

    private suspend fun prune(now: Long) {
        val cutoff = now - RETENTION_DAYS * 86_400_000L
        db.activity().pruneOlderThan(cutoff)
        db.activity().pruneBeyond(RETENTION_ROWS)
    }

    suspend fun clear() = db.activity().clear()

    /** Plain-text export, for when someone needs to send it to support. */
    suspend fun exportText(): String {
        val rows = db.activity().recent(RETENTION_ROWS)
        return buildString {
            appendLine("CloudSaver activity")
            appendLine("Exported ${Formats.dateTime(System.currentTimeMillis())}")
            appendLine()
            for (row in rows) {
                append(Formats.dateTime(row.atMs))
                append("  ")
                append(row.kind)
                if (row.count > 0) append("  ${Formats.count(row.count)} files")
                if (row.bytes > 0) append("  ${Formats.bytes(row.bytes)}")
                row.detail?.let { append("  $it") }
                appendLine()
            }
        }
    }
}
