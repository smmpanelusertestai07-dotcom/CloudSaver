package app.cloudsaver.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.util.AppLog

/**
 * Scans MediaStore images + videos on every external volume (incl. SD card) -
 * exactly what the Gallery shows. Our own output folders are excluded.
 */
class MediaScanner(private val context: Context, private val db: AppDb) {

    data class Found(
        val mediaStoreId: Long,
        val uri: String,
        val displayName: String,
        val sizeBytes: Long,
        val dateModified: Long,
        val dateTaken: Long,
        val dateAdded: Long,
        val durationMs: Long,
        val mimeType: String,
        val bucket: String?,
        val relativePath: String?,
        val isVideo: Boolean
    )

    /** Upserts everything into the DB; returns the number of new items. */
    suspend fun scan(): Int {
        val found = queryAll()
        var newItems = 0
        val now = System.currentTimeMillis()
        for (f in found) {
            val fp = Fingerprint.fp16(f.displayName, f.sizeBytes, f.dateModified)
            val existing = db.items().byFingerprint(fp)
            if (existing == null) {
                val row = ItemRow(
                    fingerprint = fp,
                    mediaStoreId = f.mediaStoreId,
                    contentUri = f.uri,
                    displayName = f.displayName,
                    sizeBytes = f.sizeBytes,
                    dateModified = f.dateModified,
                    captureAt = if (f.dateTaken > 0) f.dateTaken else f.dateModified * 1000,
                    dateAdded = f.dateAdded,
                    durationMs = f.durationMs,
                    mimeType = f.mimeType,
                    isVideo = f.isVideo,
                    bucket = f.bucket,
                    state = ItemState.NEW.name,
                    updatedAt = now
                )
                if (db.items().insert(row) != -1L) newItems++
            } else if (existing.mediaStoreId != f.mediaStoreId ||
                existing.contentUri != f.uri ||
                existing.bucket != f.bucket ||
                existing.originalMissing
            ) {
                // Moved/renamed folder: same fingerprint, refresh location only.
                db.items().update(
                    existing.copy(
                        mediaStoreId = f.mediaStoreId,
                        contentUri = f.uri,
                        bucket = f.bucket,
                        originalMissing = false,
                        updatedAt = now
                    )
                )
            }
        }
        return newItems
    }

    fun queryAll(): List<Found> {
        val out = mutableListOf<Found>()
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        for (volume in volumes) {
            try {
                queryCollection(MediaStore.Images.Media.getContentUri(volume), isVideo = false, out)
            } catch (e: Exception) {
                AppLog.log(context, "scan", "images $volume failed: ${e.message}")
            }
            try {
                queryCollection(MediaStore.Video.Media.getContentUri(volume), isVideo = true, out)
            } catch (e: Exception) {
                AppLog.log(context, "scan", "videos $volume failed: ${e.message}")
            }
        }
        return out
    }

    private fun queryCollection(
        collection: android.net.Uri,
        isVideo: Boolean,
        out: MutableList<Found>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val rel = c.getString(iRel)
                // Never re-ingest our own output copies.
                if (Defaults.isOutputPath(rel)) continue
                val name = c.getString(iName) ?: continue
                val size = c.getLong(iSize)
                if (size <= 0) continue
                val id = c.getLong(iId)
                out += Found(
                    mediaStoreId = id,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    displayName = name,
                    sizeBytes = size,
                    dateModified = c.getLong(iMod),
                    dateTaken = c.getLong(iTaken),
                    dateAdded = c.getLong(iAdded),
                    durationMs = c.getLong(iDur),
                    mimeType = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*",
                    bucket = c.getString(iBucket),
                    relativePath = rel,
                    isVideo = isVideo
                )
            }
        }
    }

    /** Fast projection of every present MediaStore id (original-missing detection). */
    fun presentIds(): Set<Long> {
        val ids = HashSet<Long>(4096)
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        for (volume in volumes) {
            for (collection in listOf(
                MediaStore.Images.Media.getContentUri(volume),
                MediaStore.Video.Media.getContentUri(volume)
            )) {
                try {
                    context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (c.moveToNext()) ids.add(c.getLong(iId))
                    }
                } catch (e: Exception) {
                    // Volume unmounted mid-query - skip.
                }
            }
        }
        return ids
    }

    /** Distinct gallery folders for the include/exclude option. */
    fun buckets(): List<String> =
        queryAll().mapNotNull { it.bucket }.distinct().sorted()
}
