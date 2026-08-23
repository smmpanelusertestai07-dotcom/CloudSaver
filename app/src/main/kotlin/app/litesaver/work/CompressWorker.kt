package app.litesaver.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.litesaver.R
import app.litesaver.core.logic.BackupScope
import app.litesaver.core.logic.Defaults
import app.litesaver.core.logic.FgsBudget
import app.litesaver.core.logic.Speed
import app.litesaver.data.db.AppDb
import app.litesaver.data.db.ItemRow
import app.litesaver.data.prefs.Options
import app.litesaver.data.prefs.OptionsRepo
import app.litesaver.engine.MaintainEngine
import app.litesaver.media.MediaScanner
import app.litesaver.media.Stager
import app.litesaver.util.LiteLog
import app.litesaver.util.Notifications
import app.litesaver.util.Permissions
import app.litesaver.util.Storage
import kotlin.math.min

/**
 * The compression worker: scan -> stage (newest first), foreground while running
 * (mediaProcessing on API 35+, dataSync on 34, none below), per-24h FGS budget
 * (stop at 5.5 h), one temp file at a time, temps cleaned at start.
 */
class CompressWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val repo = OptionsRepo.get(app)
        val options = repo.current()
        if (options.pauseAll || !options.onboardingDone) return Result.success()
        if (!Permissions.hasMediaRead(app)) return Result.success()

        Storage.cleanTemp(app)
        val db = AppDb.get(app)
        val scanner = MediaScanner(app, db)
        val stager = Stager(app, db)

        val startAt = System.currentTimeMillis()
        val sessions = FgsBudget.decode(options.fgsSessions)
        val budgetLeft = FgsBudget.remaining(sessions, startAt)
        if (budgetLeft < 5 * 60_000L) {
            LiteLog.log(app, "work", "FGS budget exhausted; will retry next period")
            return Result.success()
        }

        var foreground = false
        try {
            setForeground(foregroundInfo(app))
            foreground = true
        } catch (e: Exception) {
            // Background-start restrictions: run without FGS within JobScheduler limits.
            LiteLog.log(app, "work", "no foreground: ${e.message}")
        }

        val deadline = startAt + min(Defaults.MAX_RUN_MIN * 60_000L, budgetLeft)
        var processed = 0
        try {
            runCatching { scanner.scan() }
                .onFailure { LiteLog.log(app, "work", "scan failed: ${it.message}") }

            loop@ while (System.currentTimeMillis() < deadline && !isStopped) {
                val stageBytes = Storage.totalStageBytes(app)
                val releasedBytes = db.items().releasedBytes()
                val gate = Gates.check(app, options, stageBytes, releasedBytes)
                if (gate != null) {
                    LiteLog.log(app, "work", "gate closed: $gate")
                    break@loop
                }
                val batch = nextItems(db, options, 5)
                if (batch.isEmpty()) break@loop
                for (row in batch) {
                    if (System.currentTimeMillis() >= deadline || isStopped) break@loop
                    if (stager.stageOne(row, options)) processed++
                }
            }

            runCatching { MaintainEngine(app).run() }
                .onFailure { LiteLog.log(app, "work", "maintain failed: ${it.message}") }
        } finally {
            val endAt = System.currentTimeMillis()
            if (foreground) {
                val updated = FgsBudget.prune(sessions + (startAt to endAt), endAt)
                repo.setString(OptionsRepo.K.FGS_SESSIONS, FgsBudget.encode(updated))
            }
            repo.setLong(OptionsRepo.K.LAST_RUN_AT, endAt)
            repo.setString(OptionsRepo.K.LAST_RUN_NOTE, processed.toString())
            if (repo.current().speed == Speed.INSTANT) {
                Scheduler.enqueueInstant(app)
            }
        }
        return Result.success()
    }

    private suspend fun nextItems(db: AppDb, o: Options, limit: Int): List<ItemRow> {
        val candidates = db.items().newestNew(limit * 4)
        return candidates.asSequence()
            .filter {
                when (o.scope) {
                    BackupScope.ALL -> true
                    BackupScope.PHOTOS -> !it.isVideo
                    BackupScope.VIDEOS -> it.isVideo
                }
            }
            .filter { it.bucket == null || it.bucket !in o.excludedBuckets }
            .take(limit)
            .toList()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    companion object {
        fun foregroundInfo(context: Context): ForegroundInfo {
            val notification = Notifications.working(
                context, context.getString(R.string.notif_working_text)
            )
            return when {
                Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                    Notifications.ID_WORKING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
                Build.VERSION.SDK_INT >= 34 -> ForegroundInfo(
                    Notifications.ID_WORKING, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                else -> ForegroundInfo(Notifications.ID_WORKING, notification)
            }
        }
    }
}
