package app.cloudsaver.media

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.ReleasePlanner
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.BatchRow
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.util.Formats
import app.cloudsaver.util.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Daily release: moves staged copies into the public output folder(s)
 * Pictures/CloudSaver via MediaStore (IS_PENDING flow), sets DATE_TAKEN and
 * lastModified to the original capture date, then removes the stage file.
 * The daily release cap IS the cloud app's network cap.
 */
class Releaser(private val context: Context, private val db: AppDb) {

    suspend fun hasReleasedToday(now: Long): Boolean {
        val last = db.batches().lastReleaseAt() ?: return false
        return Formats.dayKey(last) == Formats.dayKey(now)
    }

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
    ): Int {
        val staged = db.items().staged()
            .filter { it.stagePath != null && File(it.stagePath!!).exists() }
            .filter { onlyFolder == null || it.outputFolder == onlyFolder.name }
        if (staged.isEmpty()) return 0

        val capBytes = capBytesOverride ?: options.dailyCapBytes
        val plan = ReleasePlanner.plan(
            staged.map { ReleasePlanner.Staged(it.id, it.outputBytes ?: 0L, it.captureAt) },
            capBytes
        ).let { if (maxItems != null) it.take(maxItems) else it }
        if (plan.isEmpty()) return 0

        val rowsById = staged.associateBy { it.id }
        val batchIds = HashMap<OutFolder, Long>()
        val batchBytes = HashMap<OutFolder, Long>()
        val volumeName = app.cloudsaver.util.Volumes
            .selected(context, options.storageVolume)?.mediaVolumeName
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
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
        // volume, say) must not survive: hasReleasedToday() reads MAX(releasedAt)
        // over all batches, so an empty one would convince the app it had
        // already released today, and verifyBatches skips zero-byte batches so
        // it would sit in unverified() forever.
        for ((folder, id) in batchIds) {
            val bytes = batchBytes[folder] ?: 0L
            if (bytes > 0) db.batches().setTotalBytes(id, bytes) else db.batches().deleteById(id)
        }
        return released
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
        val folder = row.outputFolder?.let { runCatching { OutFolder.valueOf(it) }.getOrNull() }
            ?: OutFolder.SINGLE
        val relPath = Defaults.outFolderRelPath(folder) + "/"
        val outName = row.outputName ?: stageFile.name
        val resolver = context.contentResolver
        val collection = if (row.isVideo) {
            MediaStore.Video.Media.getContentUri(volumeName)
        } else {
            MediaStore.Images.Media.getContentUri(volumeName)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(outName, row.mimeType))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, row.captureAt)
        }
        val itemUri = try {
            resolver.insert(collection, values) ?: return false
        } catch (e: Exception) {
            AppLog.log(context, "release", "insert failed ${row.displayName}: ${e.message}")
            return false
        }
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
            AppLog.log(context, "release", "released $actualName -> $relPath")
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
