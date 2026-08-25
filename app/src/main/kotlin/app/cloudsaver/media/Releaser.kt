package app.cloudsaver.media

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.ReleasePlanner
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.LedgerRow
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.BatchRow
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.util.Formats
import app.cloudsaver.core.logic.VolumeRules
import app.cloudsaver.util.AppLog
import kotlinx.coroutines.sync.withLock
import app.cloudsaver.util.Locks
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Moves staged copies into the public output folder(s) Pictures/CloudSaver via
 * MediaStore (IS_PENDING flow), sets DATE_TAKEN and lastModified to the
 * original capture date, then removes the stage file. The release cap IS the
 * cloud app's network cap: nothing can be uploaded that was never released.
 *
 * Releases are paced rather than dumped once a day - see [Pacing] for why a
 * copy that travels alone is the only kind the app can prove anything about.
 */
class Releaser(private val context: Context, private val db: AppDb) {

    /** Bytes already released today, against which the daily cap is measured. */
    suspend fun bytesReleasedToday(now: Long): Long =
        db.batches().bytesSince(Formats.startOfDay(now))

    /**
     * Releases staged files (newest first) up to capBytes. When [onlyFolder] is
     * set, only that folder's staged items are considered (anchor self-heal).
     * Returns the number of released files.
     */
    suspend fun releaseBatch(
        options: Options,
        now: Long,
        onlyFolder: OutFolder? = null,
        capBytesOverride: Long? = null,
        maxItems: Int? = null
        // AA3.3: one releaser at a time. The worker and "Optimise now" can
        // overlap, and two concurrent releases would double-count the batch
        // against the day's allowance.
    ): Int = Locks.release.withLock {
        val staged = db.items().staged()
            .filter { row -> row.stagePath?.let { File(it).exists() } == true }
            .filter { onlyFolder == null || it.outputFolder == onlyFolder.name }
            .filter { !alreadyDelivered(it) }
        if (staged.isEmpty()) return@withLock 0

        val capBytes = capBytesOverride ?: options.dailyCapBytes
        val plan = ReleasePlanner.plan(
            staged.map { ReleasePlanner.Staged(it.id, it.outputBytes ?: 0L, it.captureAt) },
            capBytes
        ).let { if (maxItems != null) it.take(maxItems) else it }
        if (plan.isEmpty()) return@withLock 0

        val rowsById = staged.associateBy { it.id }
        val batchIds = HashMap<OutFolder, Long>()
        val batchBytes = HashMap<OutFolder, Long>()
        // BB2.4: the chosen volume is honoured only while the probe says it
        // takes inserts; otherwise releases fall back to primary rather than
        // failing quietly, and the reason is recorded once per run.
        val chosen = app.cloudsaver.util.Volumes
            .selected(context, options.storageVolume)?.mediaVolumeName
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val decision = VolumeRules.releaseVolume(
            selectedVolume = if (chosen == MediaStore.VOLUME_EXTERNAL_PRIMARY) "" else chosen,
            selectedWritable = app.cloudsaver.util.Volumes.probeWritable(context, chosen)
        )
        val volumeName = decision.volumeName
        if (decision.fellBack) {
            AppLog.log(
                context, "release",
                "volume $chosen is not writable; releasing to internal storage instead"
            )
        }
        var released = 0
        for (id in plan) {
            val row = rowsById[id] ?: continue
            val folder = row.outputFolder?.let { runCatching { OutFolder.valueOf(it) }.getOrNull() }
                ?: OutFolder.SINGLE
            val batchId = batchIds.getOrPut(folder) {
                db.batches().insert(
                    BatchRow(
                        releasedAt = now,
                        totalBytes = 0,
                        folder = folder.name,
                        cloudPackage = cloudPackageFor(options, folder)
                    )
                )
            }
            if (releaseOne(row, batchId, now, volumeName)) {
                released++
                batchBytes[folder] = (batchBytes[folder] ?: 0L) + (row.outputBytes ?: 0L)
            }
        }
        // Record real batch sizes. A batch where every file failed (a full
        // volume, say) must not survive: it would count against the day's
        // allowance while holding nothing, and verifyBatches skips zero-byte
        // batches, so it would sit in unverified() forever.
        for ((folder, id) in batchIds) {
            val bytes = batchBytes[folder] ?: 0L
            if (bytes > 0) db.batches().setTotalBytes(id, bytes) else db.batches().deleteById(id)
        }
        // Z10.6: the 48-hour clock on the whole chain starts with the very
        // first copy that enters the upload folder.
        if (released > 0 && options.firstReleaseAt == 0L) {
            app.cloudsaver.data.prefs.OptionsRepo.get(context)
                .setLong(app.cloudsaver.data.prefs.OptionsRepo.K.FIRST_RELEASE_AT, now)
        }
        return@withLock released
    }

    /**
     * True when this exact copy has already reached the cloud.
     *
     * Without this check a cloud that removes its own uploads looks identical
     * to a user deleting a file, and the app would send the same photo again
     * every time the folder was tidied - filling the account it was meant to
     * save. The ledger is the memory that stops that loop, and it survives
     * both the item row and a reinstall.
     */
    private suspend fun alreadyDelivered(row: ItemRow): Boolean {
        val sha = row.outputSha256 ?: return false
        val seen = db.ledger().bySha(sha) ?: return false
        AppLog.log(context, "release", "skipping ${row.displayName}: already delivered")
        db.items().update(
            row.copy(
                state = ItemState.DONE.name,
                evidence = seen.evidence,
                stagePath = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        runCatching { File(row.stagePath!!).delete() }
        return true
    }

    /** Records a copy as delivered, once and for good. */
    // AA3.3: ledger writes are serialised - a delivery record and a snapshot
    // merge must never interleave on the same hash.
    suspend fun recordDelivered(row: ItemRow, evidence: String, now: Long): Unit =
        Locks.ledger.withLock {
            recordDeliveredLocked(row, evidence, now)
        }

    private suspend fun recordDeliveredLocked(row: ItemRow, evidence: String, now: Long) {
        val sha = row.outputSha256 ?: return
        db.ledger().insert(
            LedgerRow(
                outputSha256 = sha,
                fingerprint = row.fingerprint,
                displayName = row.displayName,
                outputBytes = row.outputBytes ?: 0L,
                evidence = evidence,
                confirmedAt = now
            )
        )
    }

    private fun cloudPackageFor(options: Options, folder: OutFolder): String? {
        val id = when (folder) {
            OutFolder.SINGLE -> options.cloudSingle
            OutFolder.PHOTOS -> options.cloudPhotos
            OutFolder.VIDEOS -> options.cloudVideos
        }
        return CloudApps.installedPackage(context, CloudApps.byId(id))
    }

    /** Moves one staged file into its public output folder on [volumeName]. */
    suspend fun releaseOne(
        row: ItemRow,
        batchId: Long,
        now: Long,
        volumeName: String = MediaStore.VOLUME_EXTERNAL_PRIMARY
    ): Boolean {
        val stagePath = row.stagePath ?: return false
        val stageFile = File(stagePath)
        if (!stageFile.exists()) return false
        // Z3.4: FAT32 cards top out just under 4 GB per file. Whether this
        // card is FAT32 cannot be asked, only discovered by failing - so a
        // file at the limit is routed to internal storage up front, with the
        // reason in the log, instead of failing the copy halfway through.
        val effectiveVolume = if (
            volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY &&
            !VolumeRules.fitsOnFat32(stageFile.length())
        ) {
            AppLog.log(
                context, "release",
                "${row.displayName} is ${stageFile.length()} bytes - too large for a " +
                    "FAT32 card; releasing to internal storage instead"
            )
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            volumeName
        }
        val folder = row.outputFolder?.let { runCatching { OutFolder.valueOf(it) }.getOrNull() }
            ?: OutFolder.SINGLE
        val relPath = Defaults.outFolderRelPath(folder) + "/"
        val outName = row.outputName ?: stageFile.name
        val resolver = context.contentResolver
        val collection = if (row.isVideo) {
            MediaStore.Video.Media.getContentUri(effectiveVolume)
        } else {
            MediaStore.Images.Media.getContentUri(effectiveVolume)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(outName, row.mimeType))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, row.captureAt)
        }
        var landedVolume = effectiveVolume
        var itemUri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            AppLog.log(context, "release", "insert failed ${row.displayName}: ${e.message}")
            null
        }
        // BB2.4: an SD insert that fails - or lands somewhere other than the
        // card - retries once on the primary volume with the reason recorded,
        // instead of leaving the item staged forever.
        if (itemUri != null && effectiveVolume != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            val actual = runCatching { MediaStore.getVolumeName(itemUri!!) }.getOrNull()
            if (actual != null && !actual.equals(effectiveVolume, ignoreCase = true)) {
                AppLog.log(
                    context, "release",
                    "${row.displayName} landed on $actual, not $effectiveVolume; keeping it there"
                )
                landedVolume = actual
            }
        }
        if (itemUri == null && effectiveVolume != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            AppLog.log(
                context, "release",
                "${row.displayName}: $effectiveVolume refused the insert; retrying on internal"
            )
            val primary = if (row.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            itemUri = try {
                resolver.insert(primary, values)
            } catch (e: Exception) {
                AppLog.log(context, "release", "primary retry failed: ${e.message}")
                null
            }
            landedVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
        if (itemUri == null) return false
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(stageFile).use { it.copyTo(out, 128 * 1024) }
            } ?: throw IOException("openOutputStream null")

            // Publishing makes MediaProvider scan the file and rewrite its
            // metadata, DATE_TAKEN included - from EXIF when there is any, to
            // null when there is not. Sending the date in the same update is a
            // race it usually loses, which would leave videos and screenshots
            // dated 1970 in the cloud app. Un-pend first, then stamp the date.
            resolver.update(
                itemUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
            resolver.update(
                itemUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, row.captureAt)
                },
                null, null
            )

            // MediaStore may have de-duplicated the name; read the real one back,
            // and align the file date with the original capture date.
            var actualName = outName
            try {
                @Suppress("DEPRECATION")
                val projection = arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA
                )
                resolver.query(itemUri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { actualName = it }
                        val data = c.getString(1)
                        if (!data.isNullOrEmpty()) {
                            runCatching { File(data).setLastModified(row.captureAt) }
                        }
                    }
                }
            } catch (e: Exception) {
                // Name/date polish is best effort.
            }

            db.items().update(
                row.copy(
                    state = ItemState.RELEASED.name,
                    outputUri = itemUri.toString(),
                    outputName = actualName,
                    releasedAt = now,
                    batchId = batchId,
                    stagePath = null,
                    appDeletedCopy = false,
                    updatedAt = now
                )
            )
            stageFile.delete()
            AppLog.log(context, "release", "released $actualName -> $relPath on $landedVolume")
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            AppLog.log(context, "release", "copy failed ${row.displayName}: ${e.message}")
            false
        }
    }

    companion object {
        fun mimeFor(name: String, fallback: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "mp4" -> "video/mp4"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                "dng" -> "image/x-adobe-dng"
                else -> fallback
            }
        }
    }
}
