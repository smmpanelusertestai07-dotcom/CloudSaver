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
 * Runs after the first scan following a recovery, and on every compress run
 * while a restore is watched (ReattachRules.watching): a copy may be on a
 * card put in later. Adopted rows carry no upload evidence: the file being
 * present proves it was made, not sent.
 */
class ReattachEngine(private val context: Context) {

    companion object {
        /**
         * Whether restored copies wait to be matched to the folder: until the
         * first pass after a restore, and through its watch. Until then any of
         * them may be in it: MaintainEngine counts each as there.
         */
        suspend fun pending(context: Context): Boolean = !OptionsRepo.get(context).current().copiesReattached
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
        // A clock set back since the restore restarts its watch rather than
        // stretching it to wherever the old date falls.
        val restoredAt = if (o.restoredAt > now) now.also { repo.setLong(OptionsRepo.K.RESTORED_AT, it) } else o.restoredAt
        val watching = ReattachRules.watching(restoredAt, now)
        for (entry in entries) {
            val fp = Fingerprint.fpFromOutputName(entry.name) ?: continue
            val row = db.items().byFingerprint(fp) ?: continue
            val found = entry.name == row.outputName && entry.bytes == row.outputBytes
            val readopt = row.state == ItemState.DONE.name &&
                ReattachRules.canReadopt(Evidence.parse(row.evidence), found, row.fromImport)
            if (!readopt && !ReattachRules.canAdopt(row.state, row.outputBytes != null)) continue

            // A staged file on disk is redundant once the released copy is
            // found; leaving it would count twice against the space limit.
            row.stagePath?.let { runCatching { File(it).delete() } }

            val restored = row.state == ItemState.UNKNOWN.name || readopt
            val sameCopy = restored && found
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
            val state = ReattachRules.stateWhenCopyMissing(Evidence.parse(row.evidence), watching)
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

        // While the restore is watched, the next run looks again.
        if (!watching) repo.setBool(OptionsRepo.K.COPIES_REATTACHED, true)
    }
}
