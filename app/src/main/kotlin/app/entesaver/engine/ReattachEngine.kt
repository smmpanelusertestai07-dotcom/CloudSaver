package app.entesaver.engine

import android.content.Context
import app.entesaver.core.logic.Evidence
import app.entesaver.core.logic.Fingerprint
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.OutFolder
import app.entesaver.core.logic.OutputPaths
import app.entesaver.core.logic.OutputRoots
import app.entesaver.core.logic.ReattachRules
import app.entesaver.data.db.AppDb
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.media.OutputInventory
import app.entesaver.util.Locks
import app.entesaver.util.Volumes
import java.io.File
import kotlinx.coroutines.sync.withLock

/**
 * Reunites light copies with their originals after the database was lost.
 *
 * An uninstall or "Clear data" takes Room with it but leaves every copy in
 * its folder. The snapshot usually brings the state back; when it
 * does not - the snapshot was deleted, or this is a phone-to-phone move with
 * the folder copied across - the filenames are the last thing left, and each
 * one carries its original's fingerprint.
 *
 * Runs once, after the first scan following a recovery - and again when a
 * storage volume is in that was not then, since copies on it were missed.
 * Adopted rows carry no upload evidence: the file being present proves it
 * was made, not sent.
 */
class ReattachEngine(private val context: Context) {

    companion object {
        /**
         * Whether restored copies wait to be matched to the folder. Until they
         * are, any of them may be in it: MaintainEngine counts each as there.
         */
        suspend fun pending(context: Context): Boolean {
            val o = OptionsRepo.get(context).current()
            return ReattachRules.matchPending(o.copiesReattached, o.reattachedVolumes, Volumes.mountedNames(context))
        }
    }

    suspend fun run() {
        if (!pending(context)) return
        // A restore's order: stage, then release. The rows are read and
        // written back whole below, and a restore merging meanwhile - taking
        // a row over with its history's proof - was overwritten with a copy
        // that has none; the flag set at the end then undid the restore's
        // request for another pass. Held, a restore runs wholly before this
        // or wholly after it, and after it asks again.
        Locks.stage.withLock { Locks.release.withLock { runLocked() } }
    }

    private suspend fun runLocked() {
        val db = AppDb.get(context)
        val repo = OptionsRepo.get(context)
        if (!pending(context)) return
        // Read before the listing: a volume coming in during the pass is not
        // covered by it, and asks for another.
        val volumes = Volumes.mountedNames(context)

        // A failed query looks identical to an empty folder, so a null answer
        // is left alone rather than recorded as "nothing to adopt". Only the
        // output folders, current and old: a copy that came back from the
        // cloud into some other album carries the same name, but it is not
        // waiting to be uploaded, and adopting it would have the app watch
        // a folder of the person's own.
        val o = repo.current()
        val layout = o.layout
        val entries = OutputInventory(context)
            .query(OutputRoots.watched(layout, o.pastOutputRoots, db.items().restoredRoots())) ?: return

        val now = System.currentTimeMillis()
        for (entry in entries) {
            val fp = Fingerprint.fpFromOutputName(entry.name) ?: continue
            val row = db.items().byFingerprint(fp) ?: continue
            if (!ReattachRules.canAdopt(row.state, row.outputBytes != null)) continue

            // A staged file on disk is redundant once the released copy is
            // found; leaving it would count twice against the space limit.
            row.stagePath?.let { runCatching { File(it).delete() } }

            val restored = row.state == ItemState.UNKNOWN.name
            val sameCopy = restored && entry.name == row.outputName && entry.bytes == row.outputBytes
            val evidence = ReattachRules.evidenceAfterAdopt(row.state, Evidence.parse(row.evidence), sameCopy)
            db.items().update(
                row.copy(
                    state = ReattachRules.state.name,
                    evidence = evidence.name,
                    goneReason = null,
                    confirmedAt = if (evidence == Evidence.NONE) null else row.confirmedAt,
                    outputUri = entry.uri.toString(),
                    outputName = entry.name,
                    outputBytes = entry.bytes,
                    outputSha256 = if (restored && !sameCopy) null else row.outputSha256,
                    outputFolder = OutputPaths.folderFor(entry.relPath, layout)?.name
                        ?: row.outputFolder
                        ?: (if (entry.isVideo) OutFolder.VIDEOS else OutFolder.PHOTOS).name,
                    outputRelPath = OutputRoots.normalize(entry.relPath),
                    stagePath = null,
                    // A restored copy is watched from now, like a new one: Ente's
                    // traffic before this install says nothing about it.
                    releasedAt = if (restored) now else row.releasedAt ?: now,
                    // In the folder again: an earlier leave says nothing about it.
                    leftFolderAt = null,
                    updatedAt = now
                )
            )
        }

        // Restored with evidence, and its copy is in none of the folders
        // (the ones found are RELEASED by now): Ente had it.
        for (row in db.items().restoredWithEvidence()) {
            val state = ReattachRules.stateWhenCopyMissing(Evidence.parse(row.evidence))
            // It may have been in the folder, sending, until this pass looked:
            // dated now, and later bookkeeping cannot move it.
            if (state.name != row.state) {
                db.items().update(row.copy(state = state.name, leftFolderAt = now, updatedAt = now))
            }
        }
        // So may every other restored copy this pass did not find. Until now
        // each counted as in the folder (MaintainEngine.leftDuring); from now
        // it counts as having left now, for any window still open.
        db.items().stampRestoredLeft(now)

        repo.setStringSet(OptionsRepo.K.REATTACHED_VOLUMES, volumes)
        repo.setBool(OptionsRepo.K.COPIES_REATTACHED, true)
    }
}
