package app.entesaver.media

import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.room.withTransaction
import app.entesaver.core.logic.Fingerprint
import app.entesaver.core.logic.ImportMerge
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.OutFolder
import app.entesaver.core.logic.OutputMode
import app.entesaver.core.logic.PhotoFormat
import app.entesaver.core.logic.StageRules
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.data.prefs.Options
import app.entesaver.engine.InFlight
import app.entesaver.util.DeviceTier
import app.entesaver.util.Locks
import app.entesaver.util.Storage
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

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
     *
     * One file at a time across the scheduled run and the Home trial
     * ([Locks.stage]), and only a row that is still waiting for the file it
     * describes ([StageRules]).
     */
    suspend fun stageOne(
        row: ItemRow,
        options: Options,
        predictedBytes: Long = 0,
        runRemainingMs: Long = Long.MAX_VALUE
    ): Boolean = Locks.stage.withLock {
        // stageHeld reads the row again under the lock: the one the caller
        // holds came from a list read before it started, and the other path
        // may have staged it since.
        stageHeld(row, options, predictedBytes, runRemainingMs)
    }

    /**
     * [stageOne] for a caller that already holds [Locks.stage]: the Home
     * trial, which takes it without waiting, so it never sits on "Trying"
     * behind a video the run is encoding.
     */
    suspend fun stageHeld(
        row: ItemRow,
        options: Options,
        predictedBytes: Long = 0,
        runRemainingMs: Long = Long.MAX_VALUE
    ): Boolean {
        check(Locks.stage.isLocked) { "Locks.stage must be held" }
        val fresh = db.items().byId(row.id) ?: return false
        val uriString = fresh.contentUri
        if (uriString == null) {
            if (fresh.state == ItemState.NEW.name) skip(fresh, "no_uri")
            return false
        }
        // The queue reads the state, so a row still waiting with the flag
        // set (a restore by 12.1 wrote those) is parked here as the person
        // asked, never encoded.
        if (fresh.neverOptimise) {
            if (fresh.state == ItemState.NEW.name) skip(fresh, ImportMerge.USER_EXCLUDED)
            return false
        }
        val uri = Uri.parse(uriString)
        val now = identityOf(uri)
        when (
            StageRules.verdict(
                fresh.state, fresh.originalMissing, fresh.displayName, fresh.sizeBytes,
                now?.first, now?.second
            )
        ) {
            StageRules.Verdict.TAKEN -> return false
            StageRules.Verdict.CHANGED -> {
                // Edited in place while it waited. The bytes this row
                // describes are gone, and the edited file has a row of its
                // own (or will at the next scan), so this one is retired the
                // way Free up retires it - never encoded under the old name.
                db.items().update(
                    fresh.copy(
                        state = ItemState.DONE.name,
                        originalMissing = true,
                        mediaStoreId = null,
                        contentUri = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return false
            }
            StageRules.Verdict.STAGE -> return stageLocked(fresh, uri, options, predictedBytes, runRemainingMs)
        }
    }

    /** Name and size of the file behind [uri] right now; null when unreadable. */
    private fun identityOf(uri: Uri): Pair<String, Long>? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: return@use null
            // MediaStore reports 0 for a file it has not measured yet; that
            // is not knowing, not a change.
            val size = c.getLong(1)
            if (size <= 0) return@use null
            name to size
        }
    }.getOrNull()

    private suspend fun stageLocked(
        row: ItemRow,
        uri: Uri,
        options: Options,
        predictedBytes: Long,
        runRemainingMs: Long
    ): Boolean {
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
        } catch (late: VideoCompressor.OutOfTime) {
            // The run had too little left for this clip. That says nothing
            // about the clip, so no attempt is counted: it stays in the queue
            // for a run with the whole budget, rather than going out full
            // size as an as-is copy for good.
            settle(row) { it.copy(lastError = OUT_OF_TIME, updatedAt = System.currentTimeMillis()) }
            return false
        } catch (e: Exception) {
            fail(row, ENCODE_FAILED, e.message ?: e.javaClass.simpleName)
            return false
        } catch (oom: OutOfMemoryError) {
            // A huge photo can exhaust the heap while being scaled or rotated.
            // Left uncaught this escapes the worker without counting an
            // attempt, so the same file is retried first on every run and
            // nothing behind it is ever processed. Treat it as a failure so
            // the item reaches SKIP after the usual three tries.
            fail(row, OUT_OF_MEMORY)
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
            val written = settle(row) { cur ->
                cur.copy(
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
            }
            // Settled some other way while it encoded: taken over or parked
            // by a restore, excluded, or its original gone. The copy is not
            // wanted, and staged it would be published.
            if (!written) stageFile.delete()
            written
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // By this point the temp file may already have been renamed, so
            // clean up both names rather than only the one we started with.
            result.file.delete()
            stageFile?.delete()
            fail(row, ENCODE_FAILED, e.message ?: e.javaClass.simpleName)
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

    /**
     * Counts a failed attempt; the third sets the file aside. [reason] is a
     * fixed code, which Home and Files turn into words and Home groups by;
     * [detail] is what actually went wrong, kept for the record. An exception
     * message used as the reason was shown to people as it stood, and every
     * different message made its own line on Home.
     */
    private suspend fun fail(row: ItemRow, reason: String, detail: String = reason) {
        val now = System.currentTimeMillis()
        settle(row) { cur ->
            val attempts = cur.attempts + 1
            if (attempts >= 3) {
                cur.copy(
                    state = ItemState.SKIP.name,
                    skipReason = reason,
                    attempts = attempts,
                    lastError = detail,
                    updatedAt = now
                )
            } else {
                cur.copy(attempts = attempts, lastError = detail, updatedAt = now)
            }
        }
    }

    /**
     * Writes [change] onto the row as it is now, and only while it still
     * waits for the file that was just encoded; false when it does not.
     *
     * An encode takes seconds, a video up to twenty minutes, and the row
     * read before it is stale by the end. Written back whole, it undid
     * whatever happened meanwhile - "Never optimise", the original going -
     * and the copy was published anyway. The check and the write share one
     * transaction, so nothing lands between them.
     */
    private suspend fun settle(row: ItemRow, change: (ItemRow) -> ItemRow): Boolean =
        db.withTransaction {
            val cur = db.items().byId(row.id)
            if (cur == null ||
                !StageRules.stillWaiting(
                    cur.state, cur.neverOptimise, cur.originalMissing, cur.fingerprint, row.fingerprint
                )
            ) {
                false
            } else {
                db.items().update(change(cur))
                true
            }
        }

    companion object {
        /** Why a file was set aside after three tries (see [fail]). */
        const val ENCODE_FAILED = "encode_failed"
        const val OUT_OF_MEMORY = "out_of_memory"

        /** A clip left waiting because the run ran short (see [stageOne]). */
        const val OUT_OF_TIME = "out_of_time"

        fun folderFor(isVideo: Boolean, mode: OutputMode): OutFolder = when {
            mode == OutputMode.SINGLE -> OutFolder.SINGLE
            isVideo -> OutFolder.VIDEOS
            else -> OutFolder.PHOTOS
        }
    }
}
