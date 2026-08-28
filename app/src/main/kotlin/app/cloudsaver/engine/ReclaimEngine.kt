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
import app.cloudsaver.core.logic.Presets
import app.cloudsaver.core.logic.ReclaimRules
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.ReclaimBatchRow
import app.cloudsaver.data.db.ReclaimItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.media.PhotoCompressor
import app.cloudsaver.media.VideoCompressor
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Storage
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Carries out a reclaim batch.
 *
 * The ordering here is the whole safety story: the copy is proved good, then
 * pinned, then and only then is the original's removal requested. Doing it the
 * other way round - delete, then discover the copy was truncated - is the one
 * failure this app must never have.
 */
class ReclaimEngine(private val context: Context) {

    companion object {
        /** SHA-256 of zero bytes: a hash "match" of two empty files is not proof. */
        private const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

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
     * Puts a verified light copy into the user's own album before the
     * original is touched, remaking it when nothing usable is left locally.
     *
     * It goes to Pictures/Light copies rather than anywhere under the app's
     * folder: this is the user's photo now, it has to stay in the gallery, and
     * it must not read as something to delete when CloudSaver goes.
     *
     * The bytes come from the best source still on the phone - the hidden
     * stage file, then the copy in the upload folder - each accepted only if
     * it still hashes to what was recorded. When neither survives (the normal
     * case once a cloud app has collected and removed the copy), the light
     * copy is remade from the original with the current quality setting. A
     * remade copy exists only here: it is never staged, never queued for the
     * upload folder, never sent anywhere, and the Light copies album is
     * outside everything the scanner may touch.
     *
     * Whatever the source, nothing returns non-null until the landed file has
     * been read back, hashed against the bytes that were written, and opened
     * as an image or video. The original's removal is requested only after
     * this returns - a copy that cannot be proved keeps its original.
     */
    /** Where a pinned copy landed: its uri, and whether it sits in the original's own album. */
    data class Pinned(val uri: Uri, val inPlace: Boolean)

    suspend fun pinLightCopy(row: ItemRow, options: Options, now: Long): Pinned? {
        val src = pinSource(row, options) ?: run {
            AppLog.log(context, "reclaim", "no provable light-copy source for ${row.displayName}")
            return null
        }
        return try {
            writeVerified(row, src, now, options.keptInPlace)
        } finally {
            src.temp?.delete()
        }
    }

    /** One provable byte source for a light copy. [temp] is deleted after use. */
    private class PinSource(
        val name: String,
        val sha256: String,
        val open: () -> InputStream?,
        val temp: File? = null
    )

    private suspend fun pinSource(row: ItemRow, options: Options): PinSource? {
        val resolver = context.contentResolver
        val recorded = row.outputSha256

        val stage = localCopyFile(row)
        if (stage != null && recorded != null) {
            val sha = runCatching {
                FileInputStream(stage).use { Fingerprint.sha256(it) }
            }.getOrNull()
            if (sha == recorded && stage.length() > 0) {
                val name = row.outputName ?: return null
                return PinSource(name, sha, { FileInputStream(stage) })
            }
        }

        val output = row.outputUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (output != null && recorded != null) {
            val sha = runCatching {
                resolver.openInputStream(output)?.use { Fingerprint.sha256(it) }
            }.getOrNull()
            if (sha == recorded && sha != EMPTY_SHA256) {
                val name = row.outputName ?: return null
                return PinSource(name, sha, { resolver.openInputStream(output) })
            }
        }

        // Nothing provable is left on the phone; remake from the original.
        if (row.originalMissing) return null
        val original = row.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val spec = Presets.spec(options.preset)
        val tempDir = Storage.tempDir(context, options.storageVolume)
        val result = try {
            if (row.isVideo) {
                VideoCompressor.compress(
                    context, original, row.displayName, row.mimeType, row.sizeBytes, spec,
                    options.codec, tempDir
                )
            } else {
                PhotoCompressor.compress(
                    context, original, row.displayName, row.sizeBytes, spec, tempDir
                )
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            AppLog.log(context, "reclaim", "remake failed for ${row.displayName}: ${e.message}")
            return null
        }
        if (result.file.length() <= 0) {
            result.file.delete()
            return null
        }
        val sha = runCatching {
            FileInputStream(result.file).use { Fingerprint.sha256(it) }
        }.getOrNull()
        if (sha == null) {
            result.file.delete()
            return null
        }
        val name = Fingerprint.outputName(row.displayName, row.fingerprint, result.ext)
        return PinSource(name, sha, { FileInputStream(result.file) }, temp = result.file)
    }

    private suspend fun writeVerified(
        row: ItemRow,
        src: PinSource,
        now: Long,
        inPlace: Boolean
    ): Pinned? {
        val resolver = context.contentResolver
        val collection = if (row.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // In place means: the album the original is in, under the original's
        // own name, so the gallery shows the same photo in the same place -
        // only smaller. Where that album cannot be read the copy falls back
        // to its own album rather than guessing at a folder, and says so by
        // reporting inPlace = false.
        val folder = if (inPlace) originalFolder(row) else null
        val landing = folder ?: "${Defaults.KEPT_DIR}/"
        val name = if (folder != null) inPlaceName(row, src.name) else src.name
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeForName(name, row.mimeType))
            put(MediaStore.MediaColumns.RELATIVE_PATH, landing)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            resolver.insert(collection, values) ?: return null
        } catch (e: Exception) {
            AppLog.log(context, "reclaim", "could not create light copy: ${e.message}")
            return null
        }
        return try {
            src.open()?.use { input ->
                resolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output, 128 * 1024)
                } ?: error("no output stream")
            } ?: error("no input stream")
            // Read the landed bytes back before anything else believes in
            // them. A write that "succeeded" into a full disk or a dying SD
            // card is precisely the case this copy exists to survive.
            val landed = resolver.openInputStream(target)?.use { Fingerprint.sha256(it) }
            if (landed != src.sha256 || landed == EMPTY_SHA256) error("landed bytes differ")
            if (!looksDecodable(target, src.name, row.isVideo)) error("landed file unreadable")
            // Publish, then stamp the date: MediaProvider rewrites metadata
            // during its publish scan and would lose it otherwise.
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            resolver.update(
                target,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, row.captureAt)
                    // In place, the copy has to sit exactly where the original
                    // sat in the timeline. DATE_TAKEN alone is not enough: a
                    // gallery with no date-taken on a file - which is most
                    // video - falls back to the modified date, and a fresh
                    // one would jump the photo to today, at the top of the
                    // roll, which is not "the same album, the same place".
                    if (folder != null) {
                        put(MediaStore.MediaColumns.DATE_MODIFIED, row.dateModified)
                    }
                },
                null, null
            )
            db.items().update(row.copy(keptUri = target.toString(), updatedAt = now))
            Pinned(target, inPlace = folder != null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            AppLog.log(context, "reclaim", "light copy failed for ${row.displayName}: ${e.message}")
            null
        }
    }

    /**
     * The copy's own type, from the name it will carry. The row's MIME is the
     * original's: after a HEIC-to-JPEG conversion the two disagree, and a
     * gallery that trusts the declared type over the extension shows nothing.
     */
    /**
     * The album the original actually lives in, as MediaStore spells it.
     *
     * Read from the original itself rather than assembled from its bucket
     * name: two folders on a phone can carry the same bucket name, and a
     * copy written into the wrong one of them would be a photo that moved.
     * Null when the path cannot be read, or when it belongs to this app -
     * "in place" must never mean "into our own output folder", which the
     * scanner treats as a returned copy.
     */
    private fun originalFolder(row: ItemRow): String? {
        val uri = row.contentUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val path = runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (path.isNullOrBlank()) return null
        if (Defaults.isAppOwnedPath(path)) return null
        return if (path.endsWith("/")) path else "$path/"
    }

    /**
     * The name an in-place copy carries: the original's own.
     *
     * The extension is the one the encoder actually produced, because a HEIC
     * that came out as JPEG cannot keep a .heic name - a gallery reading the
     * extension would show a broken thumbnail. Everything before the dot is
     * the original's, so the file a person finds in their album is still the
     * file they remember.
     */
    private fun inPlaceName(row: ItemRow, produced: String): String {
        val ext = produced.substringAfterLast('.', "")
        if (ext.isEmpty()) return row.displayName
        val current = row.displayName.substringAfterLast('.', "")
        if (current.equals(ext, ignoreCase = true)) return row.displayName
        val base = row.displayName.substringBeforeLast('.', row.displayName)
        return "$base.$ext"
    }

    private fun mimeForName(name: String, fallback: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> fallback
        }

    /**
     * Cheap decode probe on the formats this app itself encodes. Anything
     * else was copied as-is, where the hash already proves the bytes and a
     * probe would wrongly fail on formats this device cannot decode.
     */
    private fun looksDecodable(uri: Uri, name: String, isVideo: Boolean): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                !isVideo && (ext == "jpg" || ext == "jpeg") -> {
                    val opts = android.graphics.BitmapFactory.Options()
                        .apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, opts)
                    }
                    opts.outWidth > 0 && opts.outHeight > 0
                }
                isVideo && ext == "mp4" -> {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(context, uri)
                        mmr.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        ) != null
                    } finally {
                        runCatching { mmr.release() }
                    }
                }
                else -> true
            }
        } catch (e: Exception) {
            false
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
            // The row is re-read because it was just updated; if it has gone
            // there is nothing to write back and nothing to crash over.
            val current = db.items().byId(row.id)
            if (ok) {
                freed += row.outputBytes ?: 0L
                current?.let {
                    db.items().update(it.copy(outputUri = null, updatedAt = now))
                }
                done += Outcome(row.fingerprint, row.displayName, true)
            } else {
                current?.let {
                    db.items().update(it.copy(appDeletedCopy = false, updatedAt = now))
                }
                skipped += Outcome(row.fingerprint, row.displayName, false, "delete_refused")
            }
        }
        val batchId = recordBatch(ReclaimRules.Mode.COPIES_ONLY, done.size, freed, false, rows, done)
        activity.recordIfAny(ActivityLog.Kind.RECLAIMED, done.size, freed)
        return Result(freed, done, skipped, trashed = false, batchId = batchId)
    }

    /**
     * How the scanner will see a file that is already on the phone.
     *
     * The fingerprint is name + size + modified date, exactly as
     * [app.cloudsaver.media.MediaScanner] computes it, and it is read back
     * from MediaStore rather than predicted: the provider rewrites both the
     * name (a collision becomes "IMG_1234 (1).jpg") and the modified date
     * while publishing, and a fingerprint computed from what was asked for
     * would not match the file that actually exists.
     */
    private data class Identity(
        val uri: Uri,
        val mediaStoreId: Long,
        val displayName: String,
        val sizeBytes: Long,
        val dateModified: Long,
        val fingerprint: String
    )

    private fun identityOf(uri: Uri): Identity? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(1) ?: return@use null
            val size = c.getLong(2)
            val modified = c.getLong(3)
            if (size <= 0) return@use null
            Identity(
                uri = uri,
                mediaStoreId = c.getLong(0),
                displayName = name,
                sizeBytes = size,
                dateModified = modified,
                fingerprint = Fingerprint.fp16(name, size, modified)
            )
        }
    }.getOrNull()

    /**
     * Prepares a batch that removes originals: proves every copy, pins where
     * the mode asks for it, and returns the uris the UI must put through
     * Android's own dialog.
     */
    data class Prepared(
        val uris: List<Uri>,
        val rows: List<ItemRow>,
        val pinned: Map<Long, Pinned>,
        val skipped: List<Outcome>
    )

    suspend fun prepare(
        rows: List<ItemRow>,
        mode: ReclaimRules.Mode,
        options: Options,
        now: Long
    ): Prepared {
        val uris = mutableListOf<Uri>()
        val ready = mutableListOf<ItemRow>()
        val pinned = mutableMapOf<Long, Pinned>()
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
                // The verified light copy exists before the removal is even
                // requested; a row that cannot get one drops out here, named,
                // and its original is never put in front of Android's dialog.
                val kept = pinLightCopy(row, options, now)
                if (kept == null) {
                    skipped += Outcome(row.fingerprint, row.displayName, false, "light_copy_failed")
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
                prepared.pinned[row.id]?.let { unpinLightCopy(it.uri) }
                if (prepared.pinned.containsKey(row.id)) {
                    db.items().byId(row.id)?.let {
                        db.items().update(it.copy(keptUri = null, updatedAt = now))
                    }
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
            val inPlace = prepared.pinned[row.id]?.takeIf { it.inPlace }
            db.items().byId(row.id)?.let {
                // A copy that landed in the original's own album is a file the
                // scanner will meet again on its next pass, in a folder it is
                // meant to read. Left as it is, that pass would fingerprint it
                // as something new, queue it, and optimise an already
                // optimised photo - losing quality on every round and sending
                // a second copy of a file the cloud already holds. Re-pointing
                // the row at the new file, under the fingerprint the scanner
                // will compute for it, is what makes the two meet as the same
                // photo. The state stays a finished one, so nothing re-queues.
                val identity = inPlace?.let { p -> identityOf(p.uri) }
                db.items().update(
                    it.copy(
                        state = if (kept) ItemState.FREED_KEPT.name else ItemState.FREED.name,
                        // The original is gone either way. In place, the file
                        // standing in for it is present, which is what stops
                        // the maintenance pass reading this as a deletion -
                        // but only when that file could actually be read
                        // back. An identity we failed to read is not a file
                        // we may call present.
                        originalMissing = identity == null,
                        fingerprint = identity?.fingerprint ?: it.fingerprint,
                        contentUri = identity?.uri?.toString() ?: it.contentUri,
                        mediaStoreId = identity?.mediaStoreId ?: it.mediaStoreId,
                        displayName = identity?.displayName ?: it.displayName,
                        sizeBytes = identity?.sizeBytes ?: it.sizeBytes,
                        dateModified = identity?.dateModified ?: it.dateModified,
                        updatedAt = now
                    )
                )
            }
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
