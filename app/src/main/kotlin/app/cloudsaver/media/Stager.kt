package app.cloudsaver.media

import android.content.Context
import android.net.Uri
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Presets
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Storage
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException

/**
 * Creates the compressed copy of one item in the hidden stage dir
 * (app-specific external files dir - never indexed by MediaStore).
 * Retries with backoff are tracked per item; after 3 failures -> SKIP(reason).
 */
class Stager(private val context: Context, private val db: AppDb) {

    /** Returns true when the item is now STAGED. */
    suspend fun stageOne(row: ItemRow, options: Options): Boolean {
        val uriString = row.contentUri
        if (uriString == null) {
            skip(row, "no_uri")
            return false
        }
        val uri = Uri.parse(uriString)
        val spec = Presets.spec(options.preset)
        val tempDir = Storage.tempDir(context, options.storageVolume)
        // Compression must never make the phone feel slow, even with the
        // screen on while charging.
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        val result = try {
            if (row.isVideo) {
                VideoCompressor.compress(
                    context, uri, row.displayName, row.mimeType, row.sizeBytes, spec,
                    options.codec, tempDir
                )
            } else {
                PhotoCompressor.compress(context, uri, row.displayName, row.sizeBytes, spec, tempDir)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            fail(row, e.message ?: e.javaClass.simpleName)
            return false
        } catch (oom: OutOfMemoryError) {
            // A huge photo can exhaust the heap while being scaled or rotated.
            // Left uncaught this escapes the worker without counting an
            // attempt, so the same file is retried first on every run and
            // nothing behind it is ever processed. Treat it as a failure so
            // the item reaches SKIP after the usual three tries.
            fail(row, "out_of_memory")
            return false
        }

        var stageFile: File? = null
        return try {
            val stageName = Fingerprint.outputName(row.displayName, row.fingerprint, result.ext)
            stageFile = File(Storage.stageDir(context, options.storageVolume), stageName)
            stageFile.delete()
            if (!result.file.renameTo(stageFile)) {
                result.file.copyTo(stageFile, overwrite = true)
                result.file.delete()
            }
            val sha = FileInputStream(stageFile).use { Fingerprint.sha256(it) }
            val folder = folderFor(row.isVideo, options.outputMode)
            db.items().update(
                row.copy(
                    state = ItemState.STAGED.name,
                    stagePath = stageFile.absolutePath,
                    outputName = stageFile.name,
                    outputBytes = stageFile.length(),
                    outputSha256 = sha,
                    outputFolder = folder.name,
                    presetUsed = options.preset.name,
                    codecUsed = options.codec.name,
                    skipReason = null,
                    lastError = if (result.asIs) result.reason else null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            AppLog.log(
                context, "stage",
                "${row.displayName}: ${row.sizeBytes} -> ${stageFile.length()} (${result.reason})"
            )
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // By this point the temp file may already have been renamed, so
            // clean up both names rather than only the one we started with.
            result.file.delete()
            stageFile?.delete()
            fail(row, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private suspend fun skip(row: ItemRow, reason: String) {
        db.items().update(
            row.copy(
                state = ItemState.SKIP.name,
                skipReason = reason,
                updatedAt = System.currentTimeMillis()
            )
        )
        AppLog.log(context, "stage", "SKIP ${row.displayName}: $reason")
    }

    private suspend fun fail(row: ItemRow, error: String) {
        val attempts = row.attempts + 1
        val now = System.currentTimeMillis()
        if (attempts >= 3) {
            db.items().update(
                row.copy(
                    state = ItemState.SKIP.name,
                    skipReason = error,
                    attempts = attempts,
                    lastError = error,
                    updatedAt = now
                )
            )
        } else {
            db.items().update(row.copy(attempts = attempts, lastError = error, updatedAt = now))
        }
        AppLog.log(context, "stage", "FAIL ${row.displayName} attempt=$attempts: $error")
    }

    companion object {
        fun folderFor(isVideo: Boolean, mode: OutputMode): OutFolder = when {
            mode == OutputMode.SINGLE -> OutFolder.SINGLE
            isVideo -> OutFolder.VIDEOS
            else -> OutFolder.PHOTOS
        }
    }
}
