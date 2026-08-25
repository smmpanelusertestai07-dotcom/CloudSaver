package app.cloudsaver.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.sync.withLock
import app.cloudsaver.R
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.ReclaimRules
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.ReclaimBatchRow
import app.cloudsaver.data.db.ReclaimItemRow
import app.cloudsaver.util.AppLog
import java.io.File
import java.io.FileInputStream

/**
 * Carries out a reclaim batch.
 *
 * The ordering here is the whole safety story: the copy is proved good, then
 * pinned, then and only then is the original's removal requested. Doing it the
 * other way round - delete, then discover the copy was truncated - is the one
 * failure this app must never have.
 */
class ReclaimEngine(private val context: Context) {

    private val db = AppDb.get(context)
    private val activity = ActivityLog(context)

    /** What happened to one item, so the summary can be specific. */
    data class Outcome(
        val fingerprint: String,
        val displayName: String,
        val done: Boolean,
        val reason: String? = null
    )

    data class Result(
        val freedBytes: Long,
        val done: List<Outcome>,
        val skipped: List<Outcome>,
        val trashed: Boolean,
        val batchId: Long
    )

    /**
     * Proves the optimised copy is really there and really intact.
     *
     * A hash that matches the ledger says the bytes are the ones the cloud
     * took. Reading the first bytes back says the file is not a zero-length
     * stub left by a failed write - which a hash of a missing file would
     * never catch, because there would be nothing to hash.
     */
    suspend fun copyIsIntact(row: ItemRow): Boolean {
        val recorded = row.outputSha256 ?: return false
        val ledger = db.ledger().bySha(recorded)
        // No local copy left: the ledger and the evidence grade are all there
        // is, and the stricter rule in ReclaimRules already demanded both.
        val local = localCopyFile(row) ?: return ledger != null
        return try {
            val actual = FileInputStream(local).use { Fingerprint.sha256(it) }
            actual == recorded && local.length() > 0
        } catch (e: Exception) {
            AppLog.log(context, "reclaim", "integrity read failed for ${row.displayName}")
            false
        }
    }

    private fun localCopyFile(row: ItemRow): File? {
        val path = row.stagePath ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    /**
     * Copies the optimised file into the user's own album before the original
     * is touched.
     *
     * It goes to Pictures/Light copies rather than anywhere under the app's
     * folder: this is the user's photo now, it has to stay in the gallery, and
     * it must not read as something to delete when CloudSaver goes.
     */
    suspend fun pinLightCopy(row: ItemRow, now: Long): Uri? {
        val source = row.outputUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val name = row.outputName ?: return null
        val resolver = context.contentResolver
        val collection = if (row.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, row.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Defaults.KEPT_DIR}/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            resolver.insert(collection, values) ?: return null
        } catch (e: Exception) {
            AppLog.log(context, "reclaim", "could not create light copy: ${e.message}")
            return null
        }
        return try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output, 128 * 1024)
                } ?: error("no output stream")
            } ?: error("no input stream")
            // Publish immediately, then stamp the date: MediaProvider rewrites
            // metadata during its publish scan and would lose it otherwise.
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            resolver.update(
                target,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, row.captureAt)
                },
                null, null
            )
            db.items().update(row.copy(keptUri = target.toString(), updatedAt = now))
            target
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            AppLog.log(context, "reclaim", "light copy failed for ${row.displayName}: ${e.message}")
            null
        }
    }

    /** Undoes a pinned copy, for when the original's removal was refused. */
    fun unpinLightCopy(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /**
     * Removes only the optimised copies from the upload folder. Originals are
     * never touched here, which is what makes this the zero-risk option.
     */
    suspend fun removeCopiesOnly(rows: List<ItemRow>, now: Long): Result =
        app.cloudsaver.util.Locks.reclaim.withLock {
            removeCopiesOnlyLocked(rows, now)
        }

    private suspend fun removeCopiesOnlyLocked(rows: List<ItemRow>, now: Long): Result {
        val done = mutableListOf<Outcome>()
        val skipped = mutableListOf<Outcome>()
        var freed = 0L
        for (row in rows) {
            val uri = row.outputUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (uri == null) {
                skipped += Outcome(row.fingerprint, row.displayName, false, "no_copy")
                continue
            }
            // Mark first, so a copy that vanishes is attributed to us and not
            // read as the user deleting it.
            db.items().update(row.copy(appDeletedCopy = true, updatedAt = now))
            val ok = runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false)
            if (ok) {
                freed += row.outputBytes ?: 0L
                db.items().update(
                    db.items().byId(row.id)!!.copy(outputUri = null, updatedAt = now)
                )
                done += Outcome(row.fingerprint, row.displayName, true)
            } else {
                db.items().update(
                    db.items().byId(row.id)!!.copy(appDeletedCopy = false, updatedAt = now)
                )
                skipped += Outcome(row.fingerprint, row.displayName, false, "delete_refused")
            }
        }
        val batchId = recordBatch(ReclaimRules.Mode.COPIES_ONLY, done.size, freed, false, rows, done)
        activity.recordIfAny(ActivityLog.Kind.RECLAIMED, done.size, freed)
        return Result(freed, done, skipped, trashed = false, batchId = batchId)
    }

    /**
     * Prepares a batch that removes originals: proves every copy, pins where
     * the mode asks for it, and returns the uris the UI must put through
     * Android's own dialog.
     */
    data class Prepared(
        val uris: List<Uri>,
        val rows: List<ItemRow>,
        val pinned: Map<Long, Uri>,
        val skipped: List<Outcome>
    )

    suspend fun prepare(
        rows: List<ItemRow>,
        mode: ReclaimRules.Mode,
        now: Long
    ): Prepared {
        val uris = mutableListOf<Uri>()
        val ready = mutableListOf<ItemRow>()
        val pinned = mutableMapOf<Long, Uri>()
        val skipped = mutableListOf<Outcome>()

        for (row in rows) {
            val original = row.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (original == null) {
                skipped += Outcome(row.fingerprint, row.displayName, false, "no_original")
                continue
            }
            if (!copyIsIntact(row)) {
                // A copy that cannot be proved good means the original stays,
                // and the item goes back for another pass.
                db.items().update(
                    row.copy(state = ItemState.NEW.name, evidence = Evidence.NONE.name, updatedAt = now)
                )
                skipped += Outcome(row.fingerprint, row.displayName, false, "integrity_failed")
                continue
            }
            if (mode == ReclaimRules.Mode.REPLACE_WITH_LIGHT) {
                val kept = pinLightCopy(row, now)
                if (kept == null) {
                    skipped += Outcome(row.fingerprint, row.displayName, false, "keep_failed")
                    continue
                }
                pinned[row.id] = kept
            }
            uris += original
            ready += row
        }
        return Prepared(uris, ready, pinned, skipped)
    }

    /**
     * Records the outcome once Android has reported what the user allowed.
     * [deleted] is the set of uris that actually went.
     */
    // AA3.3: one deletion finishing at a time - Reclaim, Duplicates and the
    // legacy per-file path all end here, and their bookkeeping must not
    // interleave.
    suspend fun finish(
        prepared: Prepared,
        deleted: Set<String>,
        mode: ReclaimRules.Mode,
        trashed: Boolean,
        now: Long
    ): Result = app.cloudsaver.util.Locks.reclaim.withLock {
        finishLocked(prepared, deleted, mode, trashed, now)
    }

    private suspend fun finishLocked(
        prepared: Prepared,
        deleted: Set<String>,
        mode: ReclaimRules.Mode,
        trashed: Boolean,
        now: Long
    ): Result {
        val done = mutableListOf<Outcome>()
        val skipped = prepared.skipped.toMutableList()
        var freed = 0L
        val recorded = mutableListOf<ItemRow>()

        for (row in prepared.rows) {
            val uri = row.contentUri
            if (uri == null || uri !in deleted) {
                // Refused in the dialog: the pinned copy would otherwise be a
                // second file of the same photo sitting in the gallery.
                prepared.pinned[row.id]?.let { unpinLightCopy(it) }
                if (prepared.pinned.containsKey(row.id)) {
                    db.items().update(
                        db.items().byId(row.id)!!.copy(keptUri = null, updatedAt = now)
                    )
                }
                skipped += Outcome(row.fingerprint, row.displayName, false, "not_confirmed")
                continue
            }
            val kept = mode == ReclaimRules.Mode.REPLACE_WITH_LIGHT
            freed += if (kept) {
                (row.sizeBytes - (row.outputBytes ?: 0L)).coerceAtLeast(0L)
            } else {
                row.sizeBytes
            }
            db.items().update(
                db.items().byId(row.id)!!.copy(
                    state = if (kept) ItemState.FREED_KEPT.name else ItemState.FREED.name,
                    originalMissing = true,
                    updatedAt = now
                )
            )
            done += Outcome(row.fingerprint, row.displayName, true)
            recorded += row
        }

        val batchId = recordBatch(mode, done.size, freed, trashed, recorded, done)
        activity.recordIfAny(ActivityLog.Kind.RECLAIMED, done.size, freed)
        for (outcome in skipped) {
            // No silent failures: anything that did not happen is written down
            // with the reason, in the same place everything else is.
            activity.record(
                ActivityLog.Kind.SKIPPED,
                detail = context.getString(
                    R.string.activity_reclaim_skipped, outcome.displayName
                )
            )
        }
        AppLog.log(context, "reclaim", "freed ${freed} bytes over ${done.size} items ($mode)")
        return Result(freed, done, skipped, trashed, batchId)
    }

    private suspend fun recordBatch(
        mode: ReclaimRules.Mode,
        count: Int,
        freed: Long,
        trashed: Boolean,
        rows: List<ItemRow>,
        done: List<Outcome>
    ): Long {
        if (count <= 0) return 0
        val batchId = db.reclaim().insertBatch(
            ReclaimBatchRow(
                atMs = System.currentTimeMillis(),
                mode = mode.name,
                itemCount = count,
                freedBytes = freed,
                trashed = trashed
            )
        )
        val doneKeys = done.map { it.fingerprint }.toSet()
        db.reclaim().insertItems(
            rows.filter { it.fingerprint in doneKeys }.map { row ->
                ReclaimItemRow(
                    batchId = batchId,
                    fingerprint = row.fingerprint,
                    displayName = row.displayName,
                    album = row.bucket,
                    originalBytes = row.sizeBytes,
                    optimisedBytes = row.outputBytes ?: 0L,
                    contentUri = row.contentUri,
                    trashed = trashed
                )
            }
        )
        prune()
        return batchId
    }

    /** History lives exactly as long as the trash it describes. */
    suspend fun prune(now: Long = System.currentTimeMillis()) {
        db.reclaim().pruneBatches(now - 30L * 86_400_000L)
        db.reclaim().pruneOrphanItems()
    }

    /** A restored original is an original again, and goes back to the queue. */
    suspend fun onRestored(item: ReclaimItemRow, now: Long = System.currentTimeMillis()) {
        db.reclaim().markRestored(item.id, now)
        val row = db.items().byFingerprint(item.fingerprint) ?: return
        db.items().update(
            row.copy(
                // Its copy is still evidenced, so it is finished, not new
                // work: re-optimising a file the cloud already has would
                // upload it twice.
                state = ItemState.DONE.name,
                originalMissing = false,
                updatedAt = now
            )
        )
    }
}
