package app.cloudsaver.engine

import android.content.Context
import android.net.Uri
import app.cloudsaver.core.logic.DuplicateRules
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.util.AppLog
import kotlinx.coroutines.CancellationException

/**
 * Finds files that are byte-for-byte the same file.
 *
 * Hashing a whole gallery would be a battery complaint, so it is done the
 * cheap way: only files whose length already collides can possibly be
 * identical, and only those are read. The work runs inside the same windows
 * as compression - charging, cool, screen off - and stops at a budget, so it
 * can never be the reason a phone feels slow.
 */
class DuplicateScanner(private val context: Context) {

    private val db = AppDb.get(context)

    companion object {
        /** Files hashed per run, and the wall-clock ceiling for a pass. */
        const val MAX_FILES_PER_RUN = 200
        const val MAX_MS_PER_RUN = 60_000L
    }

    /** Hashes a slice of the collision set. Returns how many were hashed. */
    suspend fun hashSome(
        limit: Int = MAX_FILES_PER_RUN,
        budgetMs: Long = MAX_MS_PER_RUN
    ): Int {
        val deadline = System.currentTimeMillis() + budgetMs
        val pending = db.items().sizeCollisions(limit)
        if (pending.isEmpty()) return 0
        var hashed = 0
        for (row in pending) {
            if (System.currentTimeMillis() >= deadline) break
            val uri = row.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: continue
            val sha = try {
                context.contentResolver.openInputStream(uri)?.use { Fingerprint.sha256(it) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            } ?: continue
            db.items().update(row.copy(originalSha256 = sha, updatedAt = System.currentTimeMillis()))
            hashed++
        }
        if (hashed > 0) AppLog.log(context, "dupes", "hashed $hashed originals")
        return hashed
    }

    /**
     * Marks every extra copy of a group as already handled.
     *
     * Without this the pipeline optimises the same photo three times and
     * uploads it three times, and the user's "skipped" count fills with files
     * that were never a problem.
     */
    suspend fun markDuplicates(now: Long = System.currentTimeMillis()): Int {
        val groups = groups()
        var marked = 0
        for (group in groups) {
            for (extra in group.extras) {
                val row = db.items().byId(extra.id) ?: continue
                if (row.duplicateOf == group.keeper.fingerprint) continue
                // Only untouched items: one already staged or released has a
                // copy in flight, and rewriting its state would strand that.
                if (row.state != ItemState.NEW.name) continue
                db.items().update(
                    row.copy(
                        state = ItemState.SKIP.name,
                        skipReason = "duplicate",
                        duplicateOf = group.keeper.fingerprint,
                        updatedAt = now
                    )
                )
                marked++
            }
        }
        if (marked > 0) AppLog.log(context, "dupes", "marked $marked duplicates")
        return marked
    }

    /** Byte-identical groups, biggest saving first. */
    suspend fun groups(): List<DuplicateRules.Group> {
        val rows = db.items().hashedOriginals()
        val albumSizes = rows.groupingBy { it.bucket ?: "" }.eachCount()
        return DuplicateRules.group(rows.map { it.toEntry() }, albumSizes)
    }

    private fun ItemRow.toEntry() = DuplicateRules.Entry(
        id = id,
        fingerprint = fingerprint,
        displayName = displayName,
        sizeBytes = sizeBytes,
        sha256 = originalSha256,
        capturedAtMs = captureAt,
        album = bucket,
        path = contentUri ?: displayName
    )
}
