package app.cloudsaver.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.ScanSources
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
        val found = excludeOutputFolders(queryAll())
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

    /** Gallery totals for the calculator: what is there, and what is new. */
    data class Totals(
        var photoBytes: Long = 0,
        var videoBytes: Long = 0,
        var videoMinutes: Double = 0.0,
        var monthlyPhotoBytes: Long = 0,
        var monthlyVideoBytes: Long = 0,
        var photoCount: Int = 0,
        var videoCount: Int = 0,
        /**
         * False when any collection could not be read.
         *
         * A failed query and an empty gallery both produce zeros, and the two
         * mean opposite things: one is "you have no photos", the other is "we
         * do not know yet". Showing "0.00 GB" for a phone holding 22 GB - and
         * then drawing conclusions from it - came from conflating them.
         */
        var complete: Boolean = true
    ) {
        /** True once there is something real to show. */
        val measured: Boolean get() = complete && (photoCount > 0 || videoCount > 0)
    }

    /**
     * Sums the gallery without building a list of it.
     *
     * The calculator only ever needed five numbers, but the old path
     * materialised every photo in the library to get them - tens of thousands
     * of objects on a full phone, for a screen the user opens for ten seconds.
     * This walks the same cursor and adds up as it goes.
     *
     * [excludedBuckets] is the user's album exclusion set, applied here so the
     * estimate matches what will actually be backed up.
     */
    fun totals(excludedBuckets: Set<String>, nowMs: Long = System.currentTimeMillis()): Totals {
        val totals = Totals()
        val skipBuckets = excludedBuckets + excludedBucketReasons().keys
        val monthAgoSeconds = nowMs / 1000 - 30L * 86_400
        val volumes = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        // DURATION is asked for only where it exists. Several devices reject
        // it on the Images collection with "Invalid column duration", and that
        // one exception used to take every photo on the phone down with it -
        // the whole cursor was abandoned and the gallery totalled zero.
        val baseProjection = arrayOf(
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val videoProjection = baseProjection + MediaStore.MediaColumns.DURATION
        for (volume in volumes) {
            for (isVideo in listOf(false, true)) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(volume)
                } else {
                    MediaStore.Images.Media.getContentUri(volume)
                }
                val projection = if (isVideo) videoProjection else baseProjection
                try {
                    context.contentResolver.query(collection, projection, null, null, null)
                        ?.use { c ->
                            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                            val iDur = if (isVideo) {
                                c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                            } else {
                                -1
                            }
                            val iBucket =
                                c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                            val iRel =
                                c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                            while (c.moveToNext()) {
                                if (Defaults.isAppOwnedPath(c.getString(iRel))) continue
                                val bucket = c.getString(iBucket)
                                if (bucket != null && bucket in skipBuckets) continue
                                val size = c.getLong(iSize)
                                if (size <= 0) continue
                                val fresh = c.getLong(iAdded) >= monthAgoSeconds
                                if (isVideo) {
                                    totals.videoBytes += size
                                    totals.videoCount++
                                    if (iDur >= 0) {
                                        totals.videoMinutes += c.getLong(iDur) / 60_000.0
                                    }
                                    if (fresh) totals.monthlyVideoBytes += size
                                } else {
                                    totals.photoBytes += size
                                    totals.photoCount++
                                    if (fresh) totals.monthlyPhotoBytes += size
                                }
                            }
                        }
                } catch (e: Exception) {
                    // Partial numbers must never be presented as a total.
                    totals.complete = false
                    AppLog.log(context, "calc", "totals $volume failed: ${e.message}")
                }
            }
        }
        return totals
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
                // Our own output, hidden folders and other pipelines' output
                // are dropped later by excludeOutputFolders(), which can also
                // judge a folder by its contents. Only the cheap, certain case
                // is short-circuited here.
                if (Defaults.isAppOwnedPath(rel)) continue
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

    /**
     * Drops folders the app must never process: its own output, hidden
     * folders, folders named after a known pipeline, and folders whose
     * contents look like compressed copies. The content check needs the whole
     * folder in hand, which is why it happens here rather than per row.
     */
    fun excludeOutputFolders(found: List<Found>): List<Found> {
        val looksLikeOutput = found
            .groupBy { folderKey(it) }
            .filterValues { rows -> ScanSources.looksLikePipelineOutput(rows.map { it.displayName }) }
            .keys
        return found.filter { f ->
            ScanSources.exclusionReason(
                relativePath = f.relativePath,
                bucketName = f.bucket,
                looksLikeOutput = folderKey(f) in looksLikeOutput
            ) == null
        }
    }

    /** Folders the picker must show as excluded, with the reason. */
    fun excludedBucketReasons(): Map<String, ScanSources.Reason> {
        val found = queryAll()
        val looksLikeOutput = found
            .groupBy { folderKey(it) }
            .filterValues { rows -> ScanSources.looksLikePipelineOutput(rows.map { it.displayName }) }
            .keys
        val out = HashMap<String, ScanSources.Reason>()
        for (f in found) {
            val bucket = f.bucket ?: continue
            val reason = ScanSources.exclusionReason(
                relativePath = f.relativePath,
                bucketName = bucket,
                looksLikeOutput = folderKey(f) in looksLikeOutput
            ) ?: continue
            out[bucket] = reason
        }
        return out
    }

    private fun folderKey(f: Found): String = f.relativePath ?: f.bucket ?: ""

    /** Distinct gallery folders the user may actually choose between. */
    fun buckets(): List<String> =
        excludeOutputFolders(queryAll()).mapNotNull { it.bucket }.distinct().sorted()
}
