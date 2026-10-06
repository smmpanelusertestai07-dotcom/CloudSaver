package app.entesaver.engine

import android.content.Context
import app.entesaver.core.logic.Fingerprint
import app.entesaver.core.logic.OutFolder
import app.entesaver.core.logic.OutputPaths
import app.entesaver.core.logic.OutputRoots
import app.entesaver.core.logic.ReattachRules
import app.entesaver.data.db.AppDb
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.media.OutputInventory
import java.io.File

/**
 * Reunites light copies with their originals after the database was lost.
 *
 * An uninstall or "Clear data" takes Room with it but leaves every copy in
 * its folder. The snapshot usually brings the state back; when it
 * does not - the snapshot was deleted, or this is a phone-to-phone move with
 * the folder copied across - the filenames are the last thing left, and each
 * one carries its original's fingerprint.
 *
 * Runs once, after the first scan following a recovery. Adopted rows carry no
 * upload evidence: the file being present proves it was made, not sent.
 */
class ReattachEngine(private val context: Context) {

    suspend fun run() {
        val db = AppDb.get(context)
        val repo = OptionsRepo.get(context)
        if (repo.current().copiesReattached) return

        // A failed query looks identical to an empty folder, so a null answer
        // is left alone rather than recorded as "nothing to adopt". Only the
        // output folders, current and old: a copy that came back from the
        // cloud into some other album carries the same name, but it is not
        // waiting to be uploaded, and adopting it would have the app watch
        // a folder of the person's own.
        val o = repo.current()
        val layout = o.layout
        val entries = OutputInventory(context)
            .query(OutputRoots.watched(layout, o.pastOutputRoots, emptyList())) ?: return

        for (entry in entries) {
            val fp = Fingerprint.fpFromOutputName(entry.name) ?: continue
            val row = db.items().byFingerprint(fp) ?: continue
            if (!ReattachRules.canAdopt(row.state, row.outputBytes != null)) continue

            // A staged file on disk is redundant once the released copy is
            // found; leaving it would count twice against the space limit.
            row.stagePath?.let { runCatching { File(it).delete() } }

            db.items().update(
                row.copy(
                    state = ReattachRules.state.name,
                    evidence = ReattachRules.evidence.name,
                    outputUri = entry.uri.toString(),
                    outputName = entry.name,
                    outputBytes = entry.bytes,
                    outputFolder = OutputPaths.folderFor(entry.relPath, layout)?.name
                        ?: row.outputFolder
                        ?: (if (entry.isVideo) OutFolder.VIDEOS else OutFolder.PHOTOS).name,
                    outputRelPath = OutputRoots.normalize(entry.relPath),
                    stagePath = null,
                    releasedAt = row.releasedAt ?: System.currentTimeMillis()
                )
            )
        }

        repo.setBool(OptionsRepo.K.COPIES_REATTACHED, true)
    }
}
