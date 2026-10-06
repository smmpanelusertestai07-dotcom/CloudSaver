package app.entesaver.media

import android.content.Context
import android.net.Uri
import android.os.Process
import app.entesaver.core.logic.Fingerprint
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.OutFolder
import app.entesaver.core.logic.OutputMode
import app.entesaver.core.logic.PhotoFormat
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.data.prefs.Options
import app.entesaver.engine.InFlight
import app.entesaver.util.DeviceTier
import app.entesaver.util.Storage
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException

/**
 * Creates the compressed copy of one item in the hidden stage dir
 * (app-specific external files dir - never indexed by MediaStore).
 * Retries with backoff are tracked per item; after 3 failures -> SKIP(reason).
 */
class Stager(private val context: Context, private val db: AppDb) {

    /**
     * Returns true when the item is now STAGED.
     *
     * [predictedBytes] is what the profile expected this file to come out at.
     * Storing it next to the real result is what lets the app tell the user
     * how wrong its estimates have been, instead of implying they are exact.
     *
     * [runRemainingMs] is how long the caller's own run has left. A video
     * encode used to be allowed to take as long as it liked - three attempts,
     * twenty minutes each - which is how a run came back an hour after the
     * deadline it had set itself. Passing the remaining time down means the
     * deadline actually governs. A caller with no deadline of its own leaves
     * it alone and gets the ordinary budget.
     */
    suspend fun stageOne(
        row: ItemRow,
        options: Options,
        predictedBytes: Long = 0,
        runRemainingMs: Long = Long.MAX_VALUE
    ): Boolean {
        val uriString = row.contentUri
        if (uriString == null) {
            skip(row, "no_uri")
            return false
        }
        val uri = Uri.parse(uriString)
        val tempDir = Storage.tempDir(context, options.storageVolume)
        // Held to what this phone can decode without running out of memory;
        // asked only for a photo, since it reads the memory free right now.
        val photoSpec = if (row.isVideo) options.photo.spec() else DeviceTier.fit(context, options.photo.spec())
        val videoSpec = options.video.spec()
        // HEIC only once this phone has passed its own test; the test runs
        // here, in background work, the first time a photo would want it.
        val heicWorks = !row.isVideo &&
            (photoSpec.format == PhotoFormat.AUTO || photoSpec.format == PhotoFormat.HEIC) &&
            HeicSupport.probeIfNeeded(context, tempDir) == HeicSupport.State.OK
        // Compression must never make the phone feel slow, even with the
        // screen on while charging. The thread doing it belongs to a shared
        // coroutine pool, though, so the priority has to be handed back
        // afterwards: left lowered, that thread would quietly demote whatever
        // ran on it next - including the database read a screen is waiting
        // on - for the rest of the process's life.
        val tid = Process.myTid()
        val priorPriority = runCatching { Process.getThreadPriority(tid) }.getOrNull()
        runCatching {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
        }
        // Written down first: an encode that takes the whole app down throws
        // nothing, and the next run must still know which file it was.
        InFlight.begin(context, row.id)
        val result = try {
            if (row.isVideo) {
                VideoCompressor.compress(
                    context, uri, row.displayName, row.mimeType, row.sizeBytes, videoSpec, tempDir,
                    maxTotalMs = VideoCompressor.budgetFor(runRemainingMs)
                )
            } else {
                PhotoCompressor.compress(
                    context, uri, row.displayName, row.sizeBytes, photoSpec, heicWorks, tempDir
                )
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
        } finally {
            InFlight.end(context)
            priorPriority?.let { runCatching { Process.setThreadPriority(tid, it) } }
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
                    srcPixels = result.srcPixels,
                    outPixels = result.outPixels,
                    outputSha256 = sha,
                    outputFolder = folder.name,
                    // Grouped under what the settings stand for on this
                    // phone, the key estimates are read back by.
                    presetUsed = if (row.isVideo) {
                        PlannedEncode.videoKey(options)
                    } else {
                        PlannedEncode.photoKey(context, options)
                    },
                    codecUsed = result.codec?.name,
                    predictedBytes = predictedBytes,
                    skipReason = null,
                    lastError = if (result.asIs) result.reason else null,
                    updatedAt = System.currentTimeMillis()
                )
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
    }

    companion object {
        fun folderFor(isVideo: Boolean, mode: OutputMode): OutFolder = when {
            mode == OutputMode.SINGLE -> OutFolder.SINGLE
            isVideo -> OutFolder.VIDEOS
            else -> OutFolder.PHOTOS
        }
    }
}
