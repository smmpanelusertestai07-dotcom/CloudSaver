package app.cloudsaver.util

import android.content.Context
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

/**
 * Storage volumes (internal + SD card). The user can pick where the stage dir
 * and the Pictures/CloudSaver output live; scanning always covers all volumes.
 */
object Volumes {

    data class Vol(
        /** MediaStore volume name ("external_primary" or an SD UUID like "1234-abcd"). */
        val mediaVolumeName: String,
        val isPrimary: Boolean,
        val totalBytes: Long,
        val freeBytes: Long,
        val appDir: File?
    )

    fun list(context: Context): List<Vol> {
        val names = try {
            MediaStore.getExternalVolumeNames(context)
        } catch (e: Exception) {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val extraDirs = try {
            context.getExternalFilesDirs(null).filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
        val out = mutableListOf<Vol>()
        for (name in names) {
            val isPrimary = name == MediaStore.VOLUME_EXTERNAL_PRIMARY
            val dir = if (isPrimary) {
                context.getExternalFilesDir(null)
            } else {
                // SD volume names are the card UUID; the app dir path contains it.
                extraDirs.firstOrNull { it.absolutePath.contains(name, ignoreCase = true) }
            }
            val stats = try {
                dir?.let { StatFs(it.absolutePath) }
            } catch (e: Exception) {
                null
            }
            out += Vol(
                mediaVolumeName = name,
                isPrimary = isPrimary,
                totalBytes = stats?.totalBytes ?: 0L,
                freeBytes = stats?.availableBytes ?: 0L,
                appDir = dir
            )
        }
        // Primary first, deterministic order after that.
        return out.sortedWith(compareByDescending<Vol> { it.isPrimary }.thenBy { it.mediaVolumeName })
    }

    fun primary(context: Context): Vol? = list(context).firstOrNull { it.isPrimary }

    fun byName(context: Context, name: String): Vol? =
        list(context).firstOrNull { it.mediaVolumeName == name }

    /**
     * The volume selected in Options; null when that volume is currently missing
     * (SD card removed) - callers must pause safely instead of guessing.
     */
    fun selected(context: Context, storageVolume: String): Vol? {
        if (storageVolume.isEmpty()) return primary(context)
        return byName(context, storageVolume)
    }

    fun hasRemovable(context: Context): Boolean = list(context).any { !it.isPrimary }

    // ---- writability probe (BB2) -------------------------------------------

    private data class Probe(val writable: Boolean, val atMs: Long, val osFingerprint: String)

    private val probes = java.util.concurrent.ConcurrentHashMap<String, Probe>()

    /** Re-probe after this long, or immediately after an OS update. */
    private const val PROBE_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * Whether apps can really add gallery files to this volume.
     *
     * The documentation says non-primary volumes accept inserts; many phones
     * disagree, and the only way to know is to try. The probe creates one
     * pending MediaStore entry at the app's own relative path, writes a byte,
     * finalises it and deletes it - the full life of a real release, in
     * miniature. Anything failing along the way is a "no", and a "no" means
     * the option is never offered, because offering it would let every real
     * release fail quietly instead (BB2.2).
     *
     * Cached per volume: probing costs a MediaStore round-trip, and the
     * answer only changes on a remount or an OS update, so the cache expires
     * on a timer and on a fingerprint change.
     */
    fun probeWritable(context: Context, mediaVolumeName: String): Boolean {
        if (mediaVolumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) return true
        val now = System.currentTimeMillis()
        val os = android.os.Build.FINGERPRINT
        probes[mediaVolumeName]?.let { cached ->
            if (now - cached.atMs < PROBE_TTL_MS && cached.osFingerprint == os) {
                return cached.writable
            }
        }
        val writable = runProbe(context, mediaVolumeName)
        probes[mediaVolumeName] = Probe(writable, now, os)
        return writable
    }

    /** Forget cached answers - used after a volume remounts. */
    fun invalidateProbes() {
        probes.clear()
    }

    private fun runProbe(context: Context, mediaVolumeName: String): Boolean {
        val resolver = context.contentResolver
        val collection = try {
            MediaStore.Images.Media.getContentUri(mediaVolumeName)
        } catch (e: Exception) {
            return false
        }
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "probe_${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/CloudSaver/"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: android.net.Uri? = null
        return try {
            uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(0) } ?: return false
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null, null
            )
            true
        } catch (e: Exception) {
            false
        } finally {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }
}
