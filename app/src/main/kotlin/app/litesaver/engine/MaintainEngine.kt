package app.litesaver.engine

import android.content.Context
import app.litesaver.R
import app.litesaver.core.logic.Defaults
import app.litesaver.core.logic.DeletePlanner
import app.litesaver.core.logic.Evidence
import app.litesaver.core.logic.GoneReason
import app.litesaver.core.logic.ItemState
import app.litesaver.core.logic.OutFolder
import app.litesaver.core.logic.OutputMode
import app.litesaver.core.logic.StateMachine
import app.litesaver.core.logic.VerifyMath
import app.litesaver.data.CloudApps
import app.litesaver.data.db.AppDb
import app.litesaver.data.db.ItemRow
import app.litesaver.data.prefs.Options
import app.litesaver.data.prefs.OptionsRepo
import app.litesaver.media.MediaScanner
import app.litesaver.media.OutputInventory
import app.litesaver.media.Releaser
import app.litesaver.util.Formats
import app.litesaver.util.LiteLog
import app.litesaver.util.Notifications
import app.litesaver.util.Storage
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
            app.litesaver.util.Volumes.byName(context, o.storageVolume) == null
        if (volumeMissing) {
            runCatching { verifyBatches(now) }
            runCatching { ageEvidence(now) }
            runCatching { dailySnapshot(o, now) }
            if (now - o.volumeWarnedAt > 86_400_000L) {
                Notifications.warn(
                    context, Notifications.ID_WARN_SPACE,
                    context.getString(R.string.warn_volume_title),
                    context.getString(R.string.warn_volume_text)
                )
                repo.setLong(OptionsRepo.K.VOLUME_WARNED_AT, now)
            }
            LiteLog.log(context, "maintain", "storage volume missing - safe pause")
            return summary
        }

        val entries = inventory.query()

        runCatching { detectGone(o, now, entries, summary) }
            .onFailure { LiteLog.log(context, "maintain", "detectGone: ${it.message}") }
        runCatching { promoteGone(now) }
            .onFailure { LiteLog.log(context, "maintain", "promoteGone: ${it.message}") }
        runCatching { verifyBatches(now) }
            .onFailure { LiteLog.log(context, "maintain", "verify: ${it.message}") }
        runCatching { ageEvidence(now) }
            .onFailure { LiteLog.log(context, "maintain", "age: ${it.message}") }
        runCatching { selfHealStage(now) }
            .onFailure { LiteLog.log(context, "maintain", "healStage: ${it.message}") }
        runCatching { originalsPresence(now) }
            .onFailure { LiteLog.log(context, "maintain", "originals: ${it.message}") }
        if (!o.pauseAll) {
            runCatching { dailyRelease(o, now, summary) }
                .onFailure { LiteLog.log(context, "maintain", "release: ${it.message}") }
        }
        runCatching { lazyDelete(o, now, summary) }
            .onFailure { LiteLog.log(context, "maintain", "delete: ${it.message}") }
        runCatching { dailySnapshot(o, now) }
            .onFailure { LiteLog.log(context, "maintain", "snapshot: ${it.message}") }
        return summary
    }

    /** Quick pass used by the in-app "Confirm uploads" flow; returns confirmed count. */
    suspend fun confirmPass(): Int {
        val o = repo.current()
        val now = System.currentTimeMillis()
        val summary = Summary()
        runCatching { detectGone(o, now, inventory.query(), summary) }
        runCatching { promoteGone(now) }
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

    private suspend fun verifyBatches(now: Long) {
        if (!UsageVerifier.hasUsageAccess(context)) return
        for (batch in db.batches().unverified()) {
            if (batch.totalBytes <= 0) continue
            val pkg = batch.cloudPackage ?: continue
            val uid = CloudApps.uidOf(context, pkg) ?: continue
            val tx = UsageVerifier.txBytesForUid(context, uid, batch.releasedAt, now) ?: continue
            if (VerifyMath.batchVerified(tx, batch.totalBytes)) {
                db.batches().markVerified(batch.id, now)
                for (row in db.items().released().filter { it.batchId == batch.id }) {
                    val ev = evidenceOf(row)
                    if (ev.ordinal < Evidence.VERIFIED.ordinal) {
                        db.items().update(
                            row.copy(evidence = Evidence.VERIFIED.name, updatedAt = now)
                        )
                    }
                }
                LiteLog.log(context, "verify", "batch ${batch.id} verified (tx=$tx of ${batch.totalBytes})")
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
        val present = scanner.presentIds()
        if (present.isEmpty()) return // permission missing or MediaStore down - don't judge
        for (row in db.items().all()) {
            val msId = row.mediaStoreId ?: continue
            val missing = msId !in present
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
        val entries = inventory.query()
        val byFolder = entries.groupBy { normalizeRel(it.relPath) }
        val activeFolders = if (o.outputMode == OutputMode.SEPARATE) {
            listOf(OutFolder.PHOTOS, OutFolder.VIDEOS)
        } else {
            listOf(OutFolder.SINGLE)
        }
        for (folder in activeFolders) {
            val has = byFolder[Defaults.outFolderRelPath(folder)]?.isNotEmpty() == true
            if (!has) {
                val n = releaser.releaseBatch(
                    o, now, onlyFolder = folder,
                    capBytesOverride = -1L
                ).coerceAtMost(1)
                if (n > 0) {
                    summary.released += n
                    summary.healed++
                    LiteLog.log(context, "maintain", "self-heal: restored $folder")
                }
            }
        }
    }

    // ---- e) lazy delete ----------------------------------------------------------

    private suspend fun lazyDelete(o: Options, now: Long, summary: Summary) {
        // 13.A: a modified (re-signed) copy must never delete anything.
        if (app.litesaver.util.TamperCheck.isModified(context)) return
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
        val tx3d = cloudPkg?.let { pkg ->
            CloudApps.uidOf(context, pkg)?.let { uid ->
                UsageVerifier.txBytesForUid(
                    context, uid, now - Defaults.SAFETY_TX_DAYS * 86_400_000L, now
                )
            }
        }
        if (DeletePlanner.safetyPause(cloudPkg != null, tx3d, waiting)) {
            if (now - o.safetyPauseWarnedAt > 86_400_000L) {
                Notifications.warn(
                    context, Notifications.ID_WARN_SAFETY,
                    context.getString(R.string.warn_safety_title),
                    context.getString(R.string.warn_safety_text)
                )
                repo.setLong(OptionsRepo.K.SAFETY_WARNED_AT, now)
            }
            LiteLog.log(context, "delete", "safety pause active - no deletions")
            return
        }

        val entries = inventory.query()
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
                context.getString(R.string.warn_aged_text)
            )
            repo.setBool(OptionsRepo.K.AGED_WARNED, true)
        }
        LiteLog.log(context, "delete", "freed ~${Formats.bytes(plan.freedBytes)} (${plan.ids.size} copies)")
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
