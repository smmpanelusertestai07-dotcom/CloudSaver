package app.cloudsaver.engine

import android.content.Context
import app.cloudsaver.core.logic.Fingerprint
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.core.logic.ReattachRules
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.OutputInventory
import app.cloudsaver.util.AppLog

/**
 * Reunites light copies with their originals after the database was lost.
 *
 * An uninstall or "Clear data" takes Room with it but leaves every copy in
 * Pictures/CloudSaver. The snapshot usually brings the state back; when it
 * does not - the snapshot was deleted, or this is a phone-to-phone move with
 * the folder copied across - the filenames are the last thing left, and each
 * one carries its original's fingerprint.
 *
 * Runs once, after the first scan following a recovery. Adopted rows carry no
 * upload evidence: the file being present proves it was made, not sent.
 */
class ReattachEngine(private val context: Context) {

    data class Result(val adopted: Int, val scanned: Int)

    suspend fun run(): Result {
        val db = AppDb.get(context)
        val repo = OptionsRepo.get(context)
        if (repo.current().copiesReattached) return Result(0, 0)

        // A failed query looks identical to an empty folder, so a null answer
        // is left alone rather than recorded as "nothing to adopt".
        val entries = OutputInventory(context).query() ?: return Result(0, 0)

        var adopted = 0
        for (entry in entries) {
            val fp = Fingerprint.fpFromOutputName(entry.name) ?: continue
            val row = db.items().byFingerprint(fp) ?: continue
            if (!ReattachRules.canAdopt(row.state, row.outputBytes != null)) continue

            // A staged file on disk is redundant once the released copy is
            // found; leaving it would count twice against the space limit.
            row.stagePath?.let { runCatching { java.io.File(it).delete() } }

            db.items().update(
                row.copy(
                    state = ReattachRules.state.name,
                    evidence = ReattachRules.evidence.name,
                    outputUri = entry.uri.toString(),
                    outputName = entry.name,
                    outputBytes = entry.bytes,
                    outputFolder = OutputPaths.folderFor(entry.relPath)?.name ?: row.outputFolder,
                    stagePath = null,
                    releasedAt = row.releasedAt ?: System.currentTimeMillis()
                )
            )
            adopted++
        }

        repo.setBool(OptionsRepo.K.COPIES_REATTACHED, true)
        if (adopted > 0) {
            AppLog.log(
                context, "recovery",
                "re-attached $adopted copies already in the output folder"
            )
        }
        return Result(adopted, entries.size)
    }
}
