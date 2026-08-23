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
                // Moved/renamed folder: same fingerprint, refresh location.
                // An item parked in DONE only because its original had vanished
                // (nothing was ever staged or released for it) would otherwise
                // stay there forever, since DONE is terminal - put it back in
                // the queue now that the original is here again.
                val neverProcessed = existing.outputUri == null &&
                    existing.releasedAt == null &&
                    existing.stagePath == null
                val revived = existing.state == ItemState.DONE.name &&
                    existing.originalMissing &&
                    neverProcessed
                db.items().update(
                    existing.copy(
                        mediaStoreId = f.mediaStoreId,
                        contentUri = f.uri,
                        bucket = f.bucket,
                        originalMissing = false,
                        state = if (revived) ItemState.NEW.name else existing.state,
                        goneReason = if (revived) null else existing.goneReason,
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

    /**
     * Every original currently present, as volume-qualified keys.
     *
     * Returns null when any volume could not be read. Callers use this to
     * decide that originals have been deleted, and a partial answer looks
     * exactly like a mass deletion: eject an SD card mid-query and every
     * photo on it would be written off. Incomplete means "do not judge".
     */
    fun presentKeys(): Set<String>? {
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            AppLog.log(context, "scan", "volume list failed: ${e.message}")
            return null
        }
        if (volumes.isEmpty()) return null
        val keys = HashSet<String>(4096)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        for (volume in volumes) {
            for (collection in listOf(
                MediaStore.Images.Media.getContentUri(volume),
                MediaStore.Video.Media.getContentUri(volume)
            )) {
                try {
                    val cursor = context.contentResolver
                        .query(collection, projection, null, null, null)
                        ?: return null
                    cursor.use { c ->
                        val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (c.moveToNext()) keys.add(presenceKey(volume, c.getLong(iId)))
                    }
                } catch (e: Exception) {
                    AppLog.log(context, "scan", "presence query failed on $volume: ${e.message}")
                    return null
                }
            }
        }
        return keys
    }

    companion object {
        /**
         * MediaStore _ID is unique per volume, not globally, so presence has to
         * be keyed by both - otherwise a deleted SD-card photo can look present
         * because an unrelated internal file happens to share its id.
         */
        fun presenceKey(volume: String, id: Long): String = "$volume:$id"

        /** The presence key for a stored row, taking the volume from its uri. */
        fun presenceKeyOf(contentUri: String?, mediaStoreId: Long): String {
            val volume = contentUri
                ?.let { runCatching { android.net.Uri.parse(it).pathSegments.firstOrNull() }
                    .getOrNull() }
                ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            return presenceKey(volume, mediaStoreId)
        }
    }

    /** Distinct gallery folders for the include/exclude option. */
    fun buckets(): List<String> =
        queryAll().mapNotNull { it.bucket }.distinct().sorted()
}
