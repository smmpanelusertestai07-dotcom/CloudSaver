package app.cloudsaver.engine

import android.content.Context
import app.cloudsaver.R
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.DeletePlanner
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.GoneReason
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.OutputMode
import app.cloudsaver.core.logic.StateMachine
import app.cloudsaver.core.logic.VerifyMath
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
 *  a) anchor rule + self-heal   b) daily release   c) CONFIRMED detection
 *  d) VERIFIED (data-count)     e) lazy delete     f) self-heal transitions
 *  g) daily state snapshot
 */
class MaintainEngine(private val context: Context) {

    private val db = AppDb.get(context)
    private val repo = OptionsRepo.get(context)
    private val inventory = OutputInventory(context)
    private val releaser = Releaser(context, db)
    private val scanner = MediaScanner(context, db)
    private val snapshots = SnapshotStore(context, db, repo)

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

        // 13.D: selected volume (SD card) gone -> pause file work safely, keep
        // verification/bookkeeping running, never lose state.
        val volumeMissing = o.storageVolume.isNotEmpty() &&
            app.cloudsaver.util.Volumes.byName(context, o.storageVolume) == null
        if (volumeMissing) {
            step("verify") { verifyBatches(now) }
            step("age") { ageEvidence(now) }
            step("snapshot") { dailySnapshot(o, now) }
            if (now - o.volumeWarnedAt > 86_400_000L) {
                Notifications.warn(
                    context, Notifications.ID_WARN_SPACE,
                    context.getString(R.string.warn_volume_title),
                    context.getString(R.string.warn_volume_text),
                    o.warningsNotif
                )
                repo.setLong(OptionsRepo.K.VOLUME_WARNED_AT, now)
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

        step("detectGone") { detectGone(o, now, entries, summary) }
        step("promoteGone") { promoteGone(now) }
        step("verify") { verifyBatches(now) }
        step("age") { ageEvidence(now) }
        step("healStage") { selfHealStage(now) }
        step("originals") { originalsPresence(now) }
        if (!o.pauseAll) {
            step("release") { dailyRelease(o, now, summary) }
        }
        step("delete") { lazyDelete(o, now, summary) }
        step("snapshot") { dailySnapshot(o, now) }
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

    /** Quick pass used by the in-app "Confirm uploads" flow; returns confirmed count. */
    suspend fun confirmPass(): Int {
        val o = repo.current()
        val now = System.currentTimeMillis()
        val summary = Summary()
        val entries = inventory.query() ?: return 0
        step("detectGone") { detectGone(o, now, entries, summary) }
        step("promoteGone") { promoteGone(now) }
        return summary.confirmed
    }

    // ---- c) CONFIRMED / USER_DELETED detection + f) self-heal --------------------

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
        for (row in db.items().released()) {
            val folder = folderOf(row)
            val names = presentNames[Defaults.outFolderRelPath(folder)] ?: emptySet<String>()
            val outputName = row.outputName ?: continue
            if (outputName in names) continue
            val evidence = evidenceOf(row)
            val decision = StateMachine.onReleasedCopyMissing(
                appDeleted = row.appDeletedCopy,
                confirmFlowActive = confirmActive,
                evidence = evidence
            )
            if (decision.backToNew) {
                // Copy vanished with no evidence: re-compress and re-release later
                // (the cloud app de-duplicates by hash, so this is safe).
                db.items().update(
                    row.copy(
                        state = ItemState.NEW.name,
                        evidence = Evidence.NONE.name,
                        goneReason = GoneReason.USER_DELETED.name,
                        outputUri = null,
                        releasedAt = null,
                        batchId = null,
                        appDeletedCopy = false,
                        updatedAt = now
                    )
                )
                summary.healed++
            } else {
                db.items().update(
                    row.copy(
                        state = decision.state.name,
                        evidence = StateMachine.strongest(evidence, decision.evidence).name,
                        goneReason = decision.reason?.name,
                        confirmedAt = if (decision.reason == GoneReason.CONFIRMED) now else row.confirmedAt,
                        updatedAt = now
                    )
                )
                if (decision.reason == GoneReason.CONFIRMED) summary.confirmed++
            }
        }
    }

    private suspend fun promoteGone(now: Long) {
        for (row in db.items().gone()) {
            db.items().update(row.copy(state = ItemState.DONE.name, updatedAt = now))
        }
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
    private suspend fun verifyBatches(now: Long) {
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
                if (!VerifyMath.batchVerified(tx, required)) break
                db.batches().markVerified(batch.id, now)
                for (row in db.items().released().filter { it.batchId == batch.id }) {
                    val ev = evidenceOf(row)
                    if (ev.ordinal < Evidence.VERIFIED.ordinal) {
                        db.items().update(
                            row.copy(evidence = Evidence.VERIFIED.name, updatedAt = now)
                        )
                    }
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

    // ---- b) daily release + a) anchor self-heal ---------------------------------

    private suspend fun dailyRelease(o: Options, now: Long, summary: Summary) {
        if (!releaser.hasReleasedToday(now)) {
            summary.released += releaser.releaseBatch(o, now)
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
        val cloud = CloudApps.byId(o.cloudSingle)
        val cloudPkg = CloudApps.installedPackage(context, cloud)
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
                Notifications.warn(
                    context, Notifications.ID_WARN_SAFETY,
                    context.getString(R.string.warn_safety_title),
                    context.getString(R.string.warn_safety_text),
                    o.warningsNotif
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
            Notifications.warn(
                context, Notifications.ID_WARN_AGED,
                context.getString(R.string.warn_aged_title),
                context.getString(R.string.warn_aged_text),
                o.warningsNotif
            )
            repo.setBool(OptionsRepo.K.AGED_WARNED, true)
        }
        AppLog.log(context, "delete", "freed ~${Formats.bytes(plan.freedBytes)} (${plan.ids.size} copies)")
    }

    // ---- g) daily snapshot -------------------------------------------------------

    private suspend fun dailySnapshot(o: Options, now: Long) {
        val today = Formats.dayKey(now)
        if (o.lastSnapshotDay == today) return
        if (snapshots.writeDocumentsSnapshot()) {
            repo.setString(OptionsRepo.K.LAST_SNAPSHOT_DAY, today)
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private fun folderOf(row: ItemRow): OutFolder =
        row.outputFolder?.let { runCatching { OutFolder.valueOf(it) }.getOrNull() }
            ?: OutFolder.SINGLE

    private fun evidenceOf(row: ItemRow): Evidence =
        runCatching { Evidence.valueOf(row.evidence) }.getOrDefault(Evidence.NONE)

    private fun normalizeRel(rel: String): String = rel.trimEnd('/')
}
