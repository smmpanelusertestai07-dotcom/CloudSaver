package app.cloudsaver.engine

import android.content.Context
import app.cloudsaver.R
import app.cloudsaver.core.logic.CloudCapability
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.DeletePlanner
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.FirstChain
import app.cloudsaver.core.logic.EvidenceRules
import app.cloudsaver.core.logic.GoneReason
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.Pacing
import app.cloudsaver.core.logic.StateMachine
import app.cloudsaver.data.CloudApps
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.media.Releaser
import app.cloudsaver.util.Formats
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Notifications
import app.cloudsaver.util.Storage
import java.io.File

/**
 * MaintainWorker body (also runs on app open and on output-folder changes while
 * foreground):
 *  a) anchor rule + self-heal   b) paced release   c) per-file evidence
 *  d) batch evidence            e) lazy delete     f) self-heal transitions
 *  g) cloud health watchdog     h) daily state snapshot
 */
class MaintainEngine(private val context: Context) {

    private val db = AppDb.get(context)
    private val repo = OptionsRepo.get(context)
    private val inventory = OutputInventory(context)
    private val releaser = Releaser(context, db)
    private val scanner = MediaScanner(context, db)
    private val snapshots = SnapshotStore(context, db, repo)
    private val watchdog = CloudWatchdog(context)
    private val activity = ActivityLog(context)

    data class Summary(
        var confirmed: Int = 0,
        var released: Int = 0,
        var deleted: Int = 0,
        var healed: Int = 0
    )

    suspend fun run(): Summary {
        val o = repo.current()
        val now = System.currentTimeMillis()
        val summary = Summary()

        // Z10.6: the whole chain is proven by the first confirmation and
        // disproven by 48 hours of silence after the first release. Either
        // way it becomes exactly one card on Home.
        step("firstChain") {
            val confirmed = db.items().confirmedCount()
            val next = FirstChain.next(o.firstChainState, o.firstReleaseAt, confirmed, now)
            if (next != o.firstChainState) {
                repo.setString(OptionsRepo.K.FIRST_CHAIN_STATE, next)
            }
        }

        // 13.D: selected volume (SD card) gone -> pause file work safely, keep
        // verification/bookkeeping running, never lose state.
        val volumeMissing = o.storageVolume.isNotEmpty() &&
            app.cloudsaver.util.Volumes.byName(context, o.storageVolume) == null
        if (volumeMissing) {
            step("verify") { verifyBatches(o, now) }
            step("age") { ageEvidence(now) }
            step("snapshot") { dailySnapshot(o, now) }
            if (now - o.volumeWarnedAt > 86_400_000L) {
                Notifications.alert(
                    context, Notifications.ID_WARN_SPACE,
                    context.getString(R.string.warn_volume_title),
                    context.getString(R.string.warn_volume_text),
                    o, route = "storage"
                )
                repo.setLong(OptionsRepo.K.VOLUME_WARNED_AT, now)
                activity.record(
                    ActivityLog.Kind.PAUSED,
                    detail = context.getString(R.string.warn_volume_title)
                )
            }
            AppLog.log(context, "maintain", "storage volume missing - safe pause")
            return summary
        }

        // A null listing means MediaStore could not be read. Absence is
        // evidence here, so a failed read must not be mistaken for an empty
        // folder; skip the passes that interpret it and retry next hour.
        val entries = inventory.query()
        if (entries == null) {
            AppLog.log(context, "maintain", "output folder unreadable - skipping this pass")
            return summary
        }

        step("pending") { repairStalePending(now) }
        step("detectGone") { detectGone(o, now, entries, summary) }
        step("promoteGone") { promoteGone(now) }
        step("paced") { pacedEvidence(o, now) }
        step("verify") { verifyBatches(o, now) }
        step("age") { ageEvidence(now) }
        step("healStage") { selfHealStage(now) }
        step("originals") { originalsPresence(now) }
        var pauseDeletions = false
        step("cloudHealth") { pauseDeletions = cloudHealth(o, now, entries) }
        if (!o.pauseAll) {
            step("release") { pacedRelease(o, now, summary) }
        }
        if (!pauseDeletions) {
            step("delete") { lazyDelete(o, now, summary) }
        }
        step("snapshot") { dailySnapshot(o, now) }
        step("log") { logSummary(summary) }
        return summary
    }

    /**
     * Runs one maintenance step, logging a failure instead of abandoning the
     * rest of the pass. Cancellation is rethrown: runCatching would swallow it
     * (CancellationException is an Exception in Kotlin), so a stopped worker
     * would grind through every remaining step and then report success.
     */
    private inline fun step(name: String, body: () -> Unit) {
        try {
            body()
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            AppLog.log(context, "maintain", "$name: ${e.message}")
        }
    }

    private suspend fun logSummary(summary: Summary) {
        activity.recordIfAny(ActivityLog.Kind.RELEASED, summary.released)
        activity.recordIfAny(ActivityLog.Kind.BACKED_UP, summary.confirmed)
    }

    /** Quick pass used by the in-app "Verify backup" flow; returns confirmed count. */
    suspend fun confirmPass(): Int {
        val o = repo.current()
        val now = System.currentTimeMillis()
        val summary = Summary()
        val entries = inventory.query() ?: return 0
        step("pending") { repairStalePending(now) }
        step("detectGone") { detectGone(o, now, entries, summary) }
        step("promoteGone") { promoteGone(now) }
        step("paced") { pacedEvidence(o, now) }
        activity.recordIfAny(ActivityLog.Kind.BACKED_UP, summary.confirmed)
        return summary.confirmed
    }

    // ---- c) per-file evidence ----------------------------------------------------

    /**
     * A released copy left the upload folder. Deciding what that means is the
     * single most consequential judgement the app makes, because the strong
     * answer eventually offers the user's original for deletion and the wrong
     * weak answer uploads the same photo twice.
     */
    private suspend fun detectGone(
        o: Options,
        now: Long,
        entries: List<OutputInventory.Entry>,
        summary: Summary
    ) {
        val presentNames = entries.groupBy { normalizeRel(it.relPath) }
            .mapValues { (_, v) -> v.map { it.name }.toHashSet() }
        val confirmActive = o.confirmFlowStartedAt > 0 &&
            now - o.confirmFlowStartedAt <= Defaults.CONFIRM_WINDOW_MS
        val caps = watchdog.capsFor(o.cloudSingle)
        val quietMs = CloudCapability.resendQuietPeriodMs(caps)
        // A whole folder can vanish at once, and rows released together share
        // a window; querying network stats per row would then mean hundreds of
        // identical binder calls in one pass.
        val txCache = HashMap<Long, Long>()

        for (row in db.items().released()) {
            val folder = folderOf(row)
            val names = presentNames[Defaults.outFolderRelPath(folder)] ?: emptySet<String>()
            val outputName = row.outputName ?: continue
            if (outputName in names) continue

            val evidence = evidenceOf(row)
            val releasedAt = row.releasedAt ?: now
            val fileBytes = row.outputBytes ?: 0L
            val tx = txCache.getOrPut(releasedAt) { txSinceRelease(o, releasedAt, now) ?: 0L }

            // A copy that already carried evidence and then vanished is simply
            // finished. Re-sending it would say the app trusts its own earlier
            // finding less than an empty folder.
            if (!row.appDeletedCopy && evidence != Evidence.NONE && !confirmActive) {
                db.items().update(
                    row.copy(
                        state = ItemState.GONE.name,
                        goneReason = GoneReason.USER_DELETED.name,
                        updatedAt = now
                    )
                )
                continue
            }

            val verdict = if (confirmActive && !row.appDeletedCopy) {
                // The user just came back from the cloud app's own free-up
                // screen: everything that left the folder in that window left
                // because the cloud collected it.
                EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD
            } else {
                EvidenceRules.onCopyMissing(
                    appDeletedIt = row.appDeletedCopy,
                    txSinceRelease = tx,
                    fileBytes = fileBytes,
                    resendCount = row.resendCount
                )
            }

            when (verdict) {
                EvidenceRules.MissingVerdict.WE_DELETED_IT ->
                    db.items().update(
                        row.copy(
                            state = ItemState.GONE.name,
                            goneReason = GoneReason.APP_DELETED.name,
                            updatedAt = now
                        )
                    )

                EvidenceRules.MissingVerdict.PROOF_OF_UPLOAD -> {
                    db.items().update(
                        row.copy(
                            state = ItemState.GONE.name,
                            goneReason = GoneReason.CONFIRMED.name,
                            evidence = StateMachine.strongest(
                                evidence, Evidence.CONFIRMED_EXACT
                            ).name,
                            confirmedAt = now,
                            txObserved = tx,
                            updatedAt = now
                        )
                    )
                    releaser.recordDelivered(row, Evidence.CONFIRMED_EXACT.name, now)
                    noteCleanConfirmation()
                    // Only a cloud that removes its own uploads behaves this
                    // way, so this is also how the app learns what it is
                    // talking to - without ever asking the user.
                    watchdog.learnFreeUp(o.cloudSingle, now)
                    summary.confirmed++
                }

                EvidenceRules.MissingVerdict.RESEND -> {
                    // A slow upload that has not finished yet looks exactly
                    // like a lost file. Where a re-send would cost the user a
                    // duplicate, wait a day before believing the folder.
                    if (now - releasedAt < quietMs) continue
                    // Nothing to re-make it from. Sending it back to the queue
                    // would park it in "waiting to optimise" for good, and
                    // Home would count a file that can never move.
                    if (row.originalMissing) {
                        db.items().update(
                            row.copy(state = ItemState.DONE.name, updatedAt = now)
                        )
                        continue
                    }
                    if (alreadyInLedger(row)) {
                        db.items().update(
                            row.copy(state = ItemState.DONE.name, updatedAt = now)
                        )
                        continue
                    }
                    db.items().update(
                        row.copy(
                            state = ItemState.NEW.name,
                            evidence = Evidence.NONE.name,
                            goneReason = GoneReason.USER_DELETED.name,
                            outputUri = null,
                            releasedAt = null,
                            batchId = null,
                            appDeletedCopy = false,
                            resendCount = row.resendCount + 1,
                            updatedAt = now
                        )
                    )
                    summary.healed++
                }

                EvidenceRules.MissingVerdict.GIVE_UP -> {
                    // Twice is enough. Something removes this copy before the
                    // cloud ever gets it, and a third round would only be the
                    // same loop with more battery spent.
                    db.items().update(
                        row.copy(
                            state = ItemState.SKIP.name,
                            skipReason = "removed_before_upload",
                            outputUri = null,
                            updatedAt = now
                        )
                    )
                    activity.record(
                        ActivityLog.Kind.SKIPPED,
                        detail = context.getString(R.string.activity_removed_early, row.displayName),
                        count = 1,
                        filterState = ItemState.SKIP.name
                    )
                }
            }
        }
    }

    /**
     * Finishes or removes anything left half-published.
     *
     * A row stuck with IS_PENDING=1 is invisible to the cloud app and Android
     * erases it after about a week - which arrives at the user as a file that
     * silently never uploaded and then vanished. Anything older than a day is
     * either published properly or cleared away, and either way it is written
     * to Activity rather than fixed in silence.
     */
    private suspend fun repairStalePending(now: Long) {
        val cutoff = now - 86_400_000L
        var repaired = 0
        for (row in db.items().released()) {
            val uriString = row.outputUri ?: continue
            if ((row.releasedAt ?: now) > cutoff) continue
            val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: continue
            val pending = runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.MediaStore.MediaColumns.IS_PENDING),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getInt(0) == 1 else false } ?: false
            }.getOrDefault(false)
            if (!pending) continue
            val fixed = runCatching {
                context.contentResolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null, null
                ) > 0
            }.getOrDefault(false)
            if (!fixed) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                db.items().update(
                    row.copy(
                        state = ItemState.NEW.name,
                        outputUri = null,
                        releasedAt = null,
                        updatedAt = now
                    )
                )
            }
            repaired++
        }
        if (repaired > 0) {
            AppLog.log(context, "maintain", "repaired $repaired stale pending rows")
            activity.record(
                ActivityLog.Kind.RECOVERED,
                detail = context.getString(R.string.activity_pending_repaired),
                count = repaired
            )
        }
    }

    private suspend fun promoteGone(now: Long) {
        for (row in db.items().gone()) {
            db.items().update(row.copy(state = ItemState.DONE.name, updatedAt = now))
        }
    }

    /**
     * The copy travelled alone and the cloud app sent about its size.
     *
     * This is only ever attempted with exactly one copy in flight. With two,
     * a byte total says something about the pair and nothing about either, and
     * a claim about the wrong file is worse than no claim at all.
     */
    private suspend fun pacedEvidence(o: Options, now: Long) {
        if (!UsageVerifier.hasUsageAccess(context)) return
        val released = db.items().awaitingEvidence()
            .mapNotNull { row -> row.releasedAt?.let { row to it } }
        // A copy that sat there for six hours without its bytes appearing is
        // the accounting failing, not succeeding slowly. The ladder drops.
        if (released.any { (_, releasedAt) -> Pacing.isTimedOut(releasedAt, now) }) {
            notePacingFailure("a released copy timed out without confirmation")
        }
        val waiting = released
            .filter { (_, releasedAt) -> !Pacing.isTimedOut(releasedAt, now) }
            .map { (row, _) -> row }
        if (waiting.size != 1) return
        val row = waiting.first()
        val fileBytes = row.outputBytes ?: return
        val releasedAt = row.releasedAt ?: return
        val tx = txSinceRelease(o, releasedAt, now) ?: return
        if (!EvidenceRules.confirmedPaced(tx, fileBytes)) return
        db.items().update(
            row.copy(
                evidence = Evidence.CONFIRMED_PACED.name,
                confirmedAt = now,
                txObserved = tx,
                updatedAt = now
            )
        )
        releaser.recordDelivered(row, Evidence.CONFIRMED_PACED.name, now)
        noteCleanConfirmation()
        AppLog.log(
            context, "verify",
            "${row.displayName}: paced confirm (tx=$tx for $fileBytes)"
        )
    }

    // ---- d) VERIFIED (data-count) ------------------------------------------------

    /**
     * Marks batches VERIFIED once the cloud app has actually transmitted
     * enough bytes to account for them.
     *
     * Every unverified batch's window ends at now, so the windows all overlap.
     * Checking each batch on its own would let a single upload burst satisfy
     * all of them at once - five 250 MB batches "verified" by 300 MB of
     * traffic - and VERIFIED is what puts an original in front of the user for
     * deletion. So the batches are settled oldest first against one cumulative
     * total: a transmitted byte can only pay for one batch.
     */
    private suspend fun verifyBatches(o: Options, now: Long) {
        if (!UsageVerifier.hasUsageAccess(context)) return
        val pending = db.batches().unverified()
            .filter { it.totalBytes > 0 && it.cloudPackage != null }
            .sortedBy { it.releasedAt }
        for ((pkg, batches) in pending.groupBy { it.cloudPackage!! }) {
            val uid = CloudApps.uidOf(context, pkg) ?: continue
            val since = batches.first().releasedAt
            val tx = UsageVerifier.txBytesForUid(context, uid, since, now) ?: continue
            var required = 0L
            for (batch in batches) {
                required += batch.totalBytes
                if (!EvidenceRules.batchVerified(tx, required)) break
                db.batches().markVerified(batch.id, now)
                for (row in db.items().released().filter { it.batchId == batch.id }) {
                    if (evidenceOf(row).ordinal >= Evidence.VERIFIED.ordinal) continue
                    db.items().update(
                        row.copy(evidence = Evidence.VERIFIED.name, updatedAt = now)
                    )
                    // Batch-level proof is weaker than the per-file grades, but
                    // it is still proof that these bytes left the phone, so the
                    // copy must not be sent a second time.
                    releaser.recordDelivered(row, Evidence.VERIFIED.name, now)
                }
                AppLog.log(
                    context, "verify",
                    "batch ${batch.id} verified (tx=$tx covers $required cumulative)"
                )
            }
        }
    }

    private suspend fun ageEvidence(now: Long) {
        val cutoff = now - Defaults.AGED_DAYS * 86_400_000L
        for (row in db.items().released()) {
            val releasedAt = row.releasedAt ?: continue
            if (evidenceOf(row) == Evidence.NONE && releasedAt <= cutoff) {
                db.items().update(row.copy(evidence = Evidence.AGED.name, updatedAt = now))
            }
        }
    }

    // ---- f) self-heal ------------------------------------------------------------

    private suspend fun selfHealStage(now: Long) {
        for (row in db.items().staged()) {
            val path = row.stagePath
            if (path == null || !File(path).exists()) {
                db.items().update(
                    row.copy(
                        state = ItemState.NEW.name,
                        stagePath = null,
                        outputBytes = null,
                        outputSha256 = null,
                        updatedAt = now
                    )
                )
            }
        }
    }

    private suspend fun originalsPresence(now: Long) {
        // Null means the read was incomplete (permission missing, MediaStore
        // down, a volume unmounted mid-query). Judging on a partial answer
        // would write off every original it failed to see.
        val present = scanner.presentKeys() ?: return
        if (present.isEmpty()) return
        for (row in db.items().all()) {
            val msId = row.mediaStoreId ?: continue
            val missing = MediaScanner.presenceKeyOf(row.contentUri, msId) !in present
            if (missing == row.originalMissing) continue
            if (missing && row.state == ItemState.NEW.name) {
                // Nothing was processed and the original is gone: nothing to do.
                db.items().update(
                    row.copy(state = ItemState.DONE.name, originalMissing = true, updatedAt = now)
                )
            } else {
                db.items().update(row.copy(originalMissing = missing, updatedAt = now))
            }
        }
    }

    // ---- g) cloud health ---------------------------------------------------------

    /**
     * Checks that the cloud app is still doing its half of the job, and
     * returns whether deletions must be held.
     *
     * The whole product rests on some other app quietly uploading a folder.
     * When that stops being true the danger is not that copies pile up - it is
     * that the app keeps reclaiming space against evidence that has stopped
     * arriving.
     */
    private suspend fun cloudHealth(
        o: Options,
        now: Long,
        entries: List<OutputInventory.Entry>
    ): Boolean {
        val waiting = db.items().awaitingEvidence()
        val waitingBytes = waiting.sumOf { it.outputBytes ?: 0L }
        val tx = txSinceRelease(o, now - CloudWatchdog.SILENCE_MS, now)
        val shrank = o.lastOutputCount > 0 && entries.size < o.lastOutputCount
        repo.setInt(OptionsRepo.K.LAST_OUTPUT_COUNT, entries.size)

        val verdict = watchdog.check(
            cloudId = o.cloudSingle,
            waitingCopies = waiting.size,
            waitingBytes = waitingBytes,
            txLastWindow = tx,
            folderShrank = shrank,
            now = now
        )

        if (verdict.healthy) {
            if (o.cloudProblem.isNotEmpty()) {
                repo.setString(OptionsRepo.K.CLOUD_PROBLEM, "")
                activity.record(
                    ActivityLog.Kind.RESUMED,
                    detail = context.getString(R.string.activity_cloud_ok)
                )
                AppLog.log(context, "cloud", "health recovered")
            }
            return false
        }

        val problem = verdict.problem!!.name
        if (o.cloudProblem != problem) {
            repo.setString(OptionsRepo.K.CLOUD_PROBLEM, problem)
            activity.record(
                ActivityLog.Kind.CLOUD_PROBLEM,
                detail = verdict.message
            )
        }
        Notifications.alert(
            context, Notifications.ID_WARN_SAFETY,
            context.getString(R.string.warn_cloud_title),
            verdict.message ?: context.getString(R.string.warn_safety_text),
            o, dedupKey = problem, route = "activity"
        )
        AppLog.log(context, "cloud", "problem: $problem - deletions held")
        return true
    }

    /**
     * One more confirmation with nothing gone wrong: the pacing ladder climbs.
     */
    private suspend fun noteCleanConfirmation() {
        val current = repo.current()
        repo.setInt(OptionsRepo.K.CLEAN_STREAK, current.cleanConfirmStreak + 1)
        if (current.recentPacingFailure) {
            repo.setBool(OptionsRepo.K.RECENT_PACING_FAILURE, false)
        }
    }

    /**
     * A confirmation did not arrive, or did not match. The streak resets, so
     * the in-flight limit drops back down and proof samples double in
     * frequency until the accounting is trusted again.
     */
    private suspend fun notePacingFailure(reason: String) {
        val current = repo.current()
        if (current.cleanConfirmStreak == 0 && current.recentPacingFailure) return
        repo.setInt(OptionsRepo.K.CLEAN_STREAK, 0)
        repo.setBool(OptionsRepo.K.RECENT_PACING_FAILURE, true)
        AppLog.log(context, "verify", "pacing confidence reset: $reason")
    }

    // ---- b) paced release + a) anchor self-heal ---------------------------------

    /**
     * Releases a slice of the day's allowance, not the whole day at once.
     *
     * A day's worth of copies leaving together makes per-file proof
     * impossible: the cloud app's byte counter cannot then say which of them
     * arrived. Sending a few at a time - usually one - is what turns a byte
     * count into evidence about a specific file.
     */
    private suspend fun pacedRelease(o: Options, now: Long, summary: Summary) {
        val caps = watchdog.capsFor(o.cloudSingle)
        val oracle = CloudCapability.hasDisappearanceOracle(caps)
        val inFlight = db.items().awaitingEvidence().mapNotNull { it.releasedAt }
        val slots = Pacing.slotsFree(inFlight, now, oracle, o.cleanConfirmStreak)
        val maxItems = Pacing.releaseSlots(
            slotsFree = slots,
            stagedWaiting = db.items().countByState(ItemState.STAGED.name),
            perFileProofPossible = UsageVerifier.hasUsageAccess(context)
        )
        val releasedToday = releaser.bytesReleasedToday(now)
        val budget = Pacing.dailyBudgetWithCatchUp(o.dailyCapBytes, carryForward(o, now))
        val allowance = Pacing.allowanceNow(budget, releasedToday)
        if (maxItems != 0 && allowance != 0L) {
            summary.released += releaser.releaseBatch(
                o, now,
                capBytesOverride = allowance,
                maxItems = maxItems
            )
        }

        // Anchor rule / never-empty: if an active output folder has no files but
        // staged content exists for it, restore content immediately (no dummies).
        val entries = inventory.query() ?: return
        val byFolder = entries.groupBy { normalizeRel(it.relPath) }
        val activeFolders = if (o.outputMode == OutputMode.SEPARATE) {
            listOf(OutFolder.PHOTOS, OutFolder.VIDEOS)
        } else {
            listOf(OutFolder.SINGLE)
        }
        for (folder in activeFolders) {
            val has = byFolder[Defaults.outFolderRelPath(folder)]?.isNotEmpty() == true
            if (!has) {
                // Exactly one file, so the folder stops being empty without
                // shipping the whole staging backlog past the daily cap the
                // user set. The byte cap is lifted only so a single large file
                // can still anchor the folder.
                val n = releaser.releaseBatch(
                    o, now, onlyFolder = folder,
                    capBytesOverride = -1L,
                    maxItems = 1
                )
                if (n > 0) {
                    summary.released += n
                    summary.healed++
                    AppLog.log(context, "maintain", "self-heal: restored $folder")
                }
            }
        }
    }

    // ---- e) lazy delete ----------------------------------------------------------

    private suspend fun lazyDelete(o: Options, now: Long, summary: Summary) {
        // 13.A: a modified (re-signed) copy must never delete anything.
        if (app.cloudsaver.util.TamperCheck.isModified(context)) return
        val stageBytes = Storage.totalStageBytes(context)
        val outputBytes = db.items().releasedBytes()
        val extra = stageBytes + outputBytes
        val free = Storage.freeBytes(context, o.storageVolume)

        var bytesToFree = 0L
        if (o.maxExtraBytes >= 0 && extra > o.maxExtraBytes) {
            bytesToFree = maxOf(bytesToFree, extra - o.maxExtraBytes)
        }
        if (free < o.minFreeBytes) {
            bytesToFree = maxOf(bytesToFree, o.minFreeBytes - free)
        }
        if (bytesToFree <= 0) return

        val released = db.items().released()
        val waiting = released.count { evidenceOf(it).ordinal < Evidence.VERIFIED.ordinal }
        val cloudPkg = CloudApps.installedPackage(context, CloudApps.byId(o.cloudSingle))
        // "Other app" has no package to detect, so installedPackage() is null
        // for it. Treating that as "no cloud app" pauses deletion forever: the
        // copies pile up, hit the extra-space limit, and compression stops for
        // good. Availability follows the same rule the health check uses; with
        // no package there is simply no traffic to measure, so deletion falls
        // back to the age rule in DeletePlanner.
        val cloudAvailable = CloudApps.isAppInstalled(context, o.cloudSingle)
        val tx3d = cloudPkg?.let { pkg ->
            CloudApps.uidOf(context, pkg)?.let { uid ->
                UsageVerifier.txBytesForUid(
                    context, uid, now - Defaults.SAFETY_TX_DAYS * 86_400_000L, now
                )
            }
        }
        if (DeletePlanner.safetyPause(cloudAvailable, tx3d, waiting)) {
            if (now - o.safetyPauseWarnedAt > 86_400_000L) {
                Notifications.alert(
                    context, Notifications.ID_WARN_SAFETY,
                    context.getString(R.string.warn_safety_title),
                    context.getString(R.string.warn_safety_text),
                    o, dedupKey = "safety", route = "activity"
                )
                repo.setLong(OptionsRepo.K.SAFETY_WARNED_AT, now)
            }
            AppLog.log(context, "delete", "safety pause active - no deletions")
            return
        }

        val entries = inventory.query() ?: return
        val presentNames = entries.groupBy { normalizeRel(it.relPath) }
            .mapValues { (_, v) -> v.map { it.name }.toHashSet() }
        val copies = released.mapNotNull { row ->
            val name = row.outputName ?: return@mapNotNull null
            val folder = folderOf(row)
            if (name !in (presentNames[Defaults.outFolderRelPath(folder)] ?: emptySet<String>())) {
                return@mapNotNull null
            }
            DeletePlanner.Copy(
                id = row.id,
                bytes = row.outputBytes ?: 0L,
                evidence = evidenceOf(row),
                ageDays = ((now - (row.releasedAt ?: now)) / 86_400_000L).toInt(),
                folder = folder,
                captureAt = row.captureAt
            )
        }
        val plan = DeletePlanner.plan(copies, bytesToFree)
        if (plan.ids.isEmpty()) return

        for (id in plan.ids) {
            val row = db.items().byId(id) ?: continue
            val uriString = row.outputUri ?: continue
            // Mark first so a missing file is attributed to us, never to the user.
            db.items().update(row.copy(appDeletedCopy = true, updatedAt = now))
            val ok = try {
                context.contentResolver.delete(android.net.Uri.parse(uriString), null, null) > 0
            } catch (e: Exception) {
                false
            }
            if (ok) {
                db.items().update(
                    db.items().byId(id)!!.copy(
                        state = ItemState.DONE.name,
                        goneReason = GoneReason.APP_DELETED.name,
                        outputUri = null,
                        updatedAt = now
                    )
                )
                summary.deleted++
            } else {
                db.items().update(db.items().byId(id)!!.copy(appDeletedCopy = false, updatedAt = now))
            }
        }
        if (plan.agedUsed && !o.agedWarned) {
            Notifications.alert(
                context, Notifications.ID_WARN_AGED,
                context.getString(R.string.warn_aged_title),
                context.getString(R.string.warn_aged_text),
                o, dedupKey = "aged", route = "files"
            )
            repo.setBool(OptionsRepo.K.AGED_WARNED, true)
        }
        AppLog.log(context, "delete", "freed ~${Formats.bytes(plan.freedBytes)} (${plan.ids.size} copies)")
    }

    // ---- h) daily snapshot -------------------------------------------------------

    private suspend fun dailySnapshot(o: Options, now: Long) {
        val today = Formats.dayKey(now)
        if (o.lastSnapshotDay == today) return
        if (snapshots.writeSafetySnapshot()) {
            repo.setString(OptionsRepo.K.LAST_SNAPSHOT_DAY, today)
        }
    }

    /**
     * Snapshot immediately, outside the daily rhythm. Called after Free-up,
     * where the state just changed in a way that cannot be reconstructed:
     * the originals it recorded are gone.
     */
    suspend fun snapshotNow() {
        if (snapshots.writeSafetySnapshot()) {
            repo.setString(
                OptionsRepo.K.LAST_SNAPSHOT_DAY,
                Formats.dayKey(System.currentTimeMillis())
            )
        }
    }

    // ---- helpers -----------------------------------------------------------------

    /**
     * What yesterday's unused allowance leaves for today.
     *
     * A phone that was off, paused or out of range for a day should not lose
     * that day's uploads for good, or a user who travels never catches up.
     * The carry is capped at one day, because the point of the cap is the
     * mobile-data bill, not tidiness.
     */
    private suspend fun carryForward(o: Options, now: Long): Long {
        if (o.dailyCapBytes < 0) return 0L
        val today = Formats.dayKey(now)
        if (o.catchUpDay == today) return o.catchUpBytes
        val startOfToday = Formats.startOfDay(now)
        val startOfYesterday = startOfToday - 86_400_000L
        val yesterday = db.batches().bytesSince(startOfYesterday) -
            db.batches().bytesSince(startOfToday)
        val carried = Pacing.carryForward(o.dailyCapBytes, yesterday.coerceAtLeast(0L))
        repo.setString(OptionsRepo.K.CATCH_UP_DAY, today)
        repo.setLong(OptionsRepo.K.CATCH_UP_BYTES, carried)
        return carried
    }

    /** Bytes the chosen cloud app transmitted since [from]; null if unmeasurable. */
    private fun txSinceRelease(o: Options, from: Long, now: Long): Long? {
        val pkg = CloudApps.installedPackage(context, CloudApps.byId(o.cloudSingle)) ?: return null
        val uid = CloudApps.uidOf(context, pkg) ?: return null
        return UsageVerifier.txBytesForUid(context, uid, from, now)
    }

    private suspend fun alreadyInLedger(row: ItemRow): Boolean {
        val sha = row.outputSha256 ?: return false
        return db.ledger().bySha(sha) != null
    }

    private fun folderOf(row: ItemRow): OutFolder =
        row.outputFolder?.let { runCatching { OutFolder.valueOf(it) }.getOrNull() }
            ?: OutFolder.SINGLE

    private fun evidenceOf(row: ItemRow): Evidence = Evidence.parse(row.evidence)

    private fun normalizeRel(rel: String): String = rel.trimEnd('/')
}
