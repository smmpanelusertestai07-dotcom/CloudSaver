package app.cloudsaver.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

object Storage {

    /** Free bytes on the volume the app is configured to use ("" = internal). */
    fun freeBytes(context: Context, storageVolume: String = ""): Long = try {
        val vol = Volumes.selected(context, storageVolume)
        val base = vol?.appDir ?: context.getExternalFilesDir(null) ?: Environment.getDataDirectory()
        StatFs(base.absolutePath).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    /** Stage dir on the selected volume; falls back to internal if missing. */
    fun stageDir(context: Context, storageVolume: String = ""): File {
        val vol = Volumes.selected(context, storageVolume)
        val base = vol?.appDir ?: context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "stage")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun tempDir(context: Context, storageVolume: String = ""): File {
        val dir = File(stageDir(context, storageVolume), "tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Stage size across ALL volumes (files staged before a volume switch count too). */
    fun totalStageBytes(context: Context): Long =
        Volumes.list(context).sumOf { vol ->
            vol.appDir?.let { dirSize(File(it, "stage")) } ?: 0L
        }

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            val children = f.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else size += c.length()
            }
        }
        return size
    }

    /** Deletes leftover temp files on every volume; cheap (one temp at a time). */
    fun cleanTemp(context: Context): Long {
        var freed = 0L
        for (vol in Volumes.list(context)) {
            try {
                val tmp = File(File(vol.appDir ?: continue, "stage"), "tmp")
                tmp.listFiles()?.forEach { f ->
                    freed += f.length()
                    f.delete()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return freed
    }
}
