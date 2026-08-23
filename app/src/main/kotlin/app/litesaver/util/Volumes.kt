package app.litesaver.util

import android.content.Context
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

/**
 * Storage volumes (internal + SD card). The user can pick where the stage dir
 * and the Pictures/LiteSaver output live; scanning always covers all volumes.
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
}
