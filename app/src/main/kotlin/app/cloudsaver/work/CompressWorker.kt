package app.cloudsaver.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.cloudsaver.R
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.FgsBudget
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.RunDecider
import app.cloudsaver.core.logic.SpeedMode
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.engine.ActivityLog
import app.cloudsaver.engine.MaintainEngine
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.Stager
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Notifications
import app.cloudsaver.util.Permissions
import app.cloudsaver.util.Storage
import kotlin.math.min

/**
 * The compression worker. WorkManager only guarantees "battery not low"; every
 * real condition comes from [RunDecider], evaluated at start and between every
 * single item, so the run stops cleanly the moment the phone is picked up, the
 * battery drops, Battery Saver comes on or the daily budget runs out.
 *
 * Nothing here ever holds a wakelock while waiting: a blocked run simply ends
 * and the next periodic run re-evaluates.
 */
class CompressWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The periodic run, the FAST content trigger and "Run now" are three
        // different unique work names, so WorkManager will happily run them at
        // once. They share one staging directory and one temp directory, and
        // every run starts by clearing the temp directory - which would delete
        // a sibling's half-written encode out from under it. One at a time.
        if (!running.compareAndSet(false, true)) {
            AppLog.log(applicationContext, "work", "another run is active; skipping")
            return Result.success()
        }
        return try {
            runOnce()
        } finally {
            running.set(false)
        }
    }

    private suspend fun runOnce(): Result {
        val app = applicationContext
        val repo = OptionsRepo.get(app)
        val options = repo.current()
        val manual = inputData.getBoolean(KEY_MANUAL, false)

        if (!options.onboardingDone) return Result.success()
        if (options.pauseAll && !manual) return Result.success()
        if (!Permissions.hasMediaRead(app)) return Result.success()

        val db = AppDb.get(app)
        val dayBudget = DayBudget(app)
        val startAt = System.currentTimeMillis()

        // First decision: is there any reason to spin up at all?
        var power = Gates.readPower(app, options.lastInteractiveAt, startAt)
        if (power.screenInteractive) repo.setLong(OptionsRepo.K.LAST_INTERACTIVE_AT, startAt)
        var plan = plan(options, power, dayBudget.read(startAt), manual)
        repo.setString(OptionsRepo.K.WAIT_REASON, plan.wait.name)
        if (!plan.canRun) {
            AppLog.log(app, "work", "not starting: ${plan.wait}")
            reschedule(app, repo)
            return Result.success()
        }

        Storage.cleanTemp(app)
        val scanner = MediaScanner(app, db)
        val stager = Stager(app, db)

        val sessions = FgsBudget.decode(options.fgsSessions)
        val fgsLeft = FgsBudget.remaining(sessions, startAt)
        if (fgsLeft < 5 * 60_000L) {
            AppLog.log(app, "work", "foreground-service budget exhausted; next period")
            reschedule(app, repo)
            return Result.success()
        }

        var foreground = false
        try {
            setForeground(foregroundInfo(app))
            foreground = true
        } catch (e: Exception) {
            // Background-start restrictions: run inside plain JobScheduler limits.
            AppLog.log(app, "work", "no foreground: ${e.message}")
        }

        val deadline = startAt + min(Defaults.MAX_RUN_MIN * 60_000L, fgsLeft)
        var processed = 0
        var videoMsOnBattery = 0L
        var photosOnBattery = 0
        try {
            runCatching { scanner.scan() }
                .onFailure { AppLog.log(app, "work", "scan failed: ${it.message}") }

            loop@ while (System.currentTimeMillis() < deadline && !isStopped) {
                val now = System.currentTimeMillis()
                // Re-read options every round: Pause or a mode change must take
                // effect during a run, not only on the next one.
                val live = repo.current()
                power = Gates.readPower(app, live.lastInteractiveAt, now)
                if (power.screenInteractive) repo.setLong(OptionsRepo.K.LAST_INTERACTIVE_AT, now)

                val budget = dayBudget.read(now).let {
                    // Count what this run already spent before it is flushed.
                    it.copy(
                        videoEncodeMs = it.videoEncodeMs + videoMsOnBattery,
                        photosOnBattery = it.photosOnBattery + photosOnBattery
                    )
                }
                plan = plan(live, power, budget, manual)
                repo.setString(OptionsRepo.K.WAIT_REASON, plan.wait.name)
                if (!plan.canRun) {
                    AppLog.log(app, "work", "stopping: ${plan.wait}")
                    break@loop
                }

                val resource = Gates.resourceGate(
                    app, live, Storage.totalStageBytes(app), db.items().releasedBytes()
                )
                if (resource != null) {
                    AppLog.log(app, "work", "resource gate: $resource")
                    break@loop
                }

                val batch = nextItems(db, live, plan, 5)
                if (batch.isEmpty()) {
                    // Nothing left that this power state allows. If photos are
                    // the only thing waiting, say why they are not running.
                    if (!plan.photos && db.items().countByState("NEW") > 0) {
                        repo.setString(OptionsRepo.K.WAIT_REASON, RunDecider.Wait.PHOTO_CAP.name)
                    }
                    break@loop
                }
                for (row in batch) {
                    if (System.currentTimeMillis() >= deadline || isStopped) break@loop
                    val itemStart = System.currentTimeMillis()
                    val ok = stager.stageOne(row, live)
                    val took = System.currentTimeMillis() - itemStart
                    if (ok) {
                        processed++
                        if (!power.plugged) {
                            if (row.isVideo) videoMsOnBattery += took else photosOnBattery++
                        }
                    }
                    // Re-check power between items, not just between batches.
                    val mid = System.currentTimeMillis()
                    power = Gates.readPower(app, repo.current().lastInteractiveAt, mid)
                    if (power.screenInteractive) {
                        repo.setLong(OptionsRepo.K.LAST_INTERACTIVE_AT, mid)
                    }
                    val midBudget = dayBudget.read(mid).let {
                        it.copy(
                            videoEncodeMs = it.videoEncodeMs + videoMsOnBattery,
                            photosOnBattery = it.photosOnBattery + photosOnBattery
                        )
                    }
                    val midPlan = plan(live, power, midBudget, manual)
                    repo.setString(OptionsRepo.K.WAIT_REASON, midPlan.wait.name)
                    if (!midPlan.canRun) {
                        AppLog.log(app, "work", "stopping mid-batch: ${midPlan.wait}")
                        break@loop
                    }
                }
            }

            if (processed > 0) {
                runCatching {
                    val saved = db.items().savedBytesSince(startAt)
                    ActivityLog(app).record(
                        ActivityLog.Kind.OPTIMISED,
                        count = processed,
                        bytes = saved,
                        filterState = ItemState.STAGED.name
                    )
                }
            }
            runCatching { MaintainEngine(app).run() }
                .onFailure { AppLog.log(app, "work", "maintain failed: ${it.message}") }
        } finally {
            val endAt = System.currentTimeMillis()
            runCatching {
                dayBudget.addVideoEncode(endAt, videoMsOnBattery)
                dayBudget.addPhotos(endAt, photosOnBattery)
            }
            if (foreground) {
                val updated = FgsBudget.prune(sessions + (startAt to endAt), endAt)
                repo.setString(OptionsRepo.K.FGS_SESSIONS, FgsBudget.encode(updated))
            }
            repo.setLong(OptionsRepo.K.LAST_RUN_AT, endAt)
            repo.setString(OptionsRepo.K.LAST_RUN_NOTE, processed.toString())
            // Never leave a "working" icon in the status bar once the run is
            // over, whatever ended it.
            Notifications.clearWorking(app)
            reschedule(app, repo)
        }
        return Result.success()
    }

    private fun plan(
        o: Options,
        power: RunDecider.Power,
        budget: RunDecider.Budget,
        manual: Boolean
    ): RunDecider.Plan = if (manual) {
        RunDecider.decideManual(power)
    } else {
        RunDecider.decide(o.speed, power, budget, paused = o.pauseAll)
    }

    /** FAST re-arms its content trigger after every run (triggers are one-shot). */
    private suspend fun reschedule(context: Context, repo: OptionsRepo) {
        if (repo.current().speed == SpeedMode.FAST) {
            Scheduler.enqueueContentTrigger(context)
        }
    }

    /**
     * What to work on next: this month's photos first, then the biggest of the
     * backlog.
     *
     * Pure newest-first feels responsive - a photo taken this morning is
     * backed up by lunch - but on a phone with ten years of gallery it then
     * grinds through a thousand old screenshots for almost no space. Once the
     * recent window is clear, size ordering frees the most per minute of
     * encoding, so the numbers on Home start moving.
     */
    private suspend fun nextItems(
        db: AppDb,
        o: Options,
        plan: RunDecider.Plan,
        limit: Int
    ): List<ItemRow> {
        // What the user asked for, narrowed to what this power state allows.
        val photos = plan.photos && o.scope != BackupScope.VIDEOS
        val videos = plan.videos && o.scope != BackupScope.PHOTOS
        if (!photos && !videos) return emptyList()
        return db.items().nextByPriority(
            photos = photos,
            videos = videos,
            excludedBuckets = o.excludedBuckets,
            freshAfter = System.currentTimeMillis() - FRESH_WINDOW_MS,
            limit = limit
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    companion object {
        /** Guards the staging and temp directories against concurrent runs. */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Anything captured this recently counts as "what the user is thinking about". */
        const val FRESH_WINDOW_MS = 30L * 86_400_000L

        const val KEY_MANUAL = "manual"

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
