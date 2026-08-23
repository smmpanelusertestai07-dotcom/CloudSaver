package app.cloudsaver.engine

import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.util.AppLog

/**
 * Runs once per launch, before anything else touches the database.
 *
 * Room is the source of truth, but it lives in app data, which "Clear data"
 * and an uninstall both wipe. The hidden snapshots outlive that, so if the
 * database is empty and a valid snapshot exists, the state comes back on its
 * own - the user is not asked to restore anything, and Free-up offers nothing
 * it cannot justify, because imported items arrive without evidence.
 */
class StartupRecovery(private val context: Context) {

    data class Result(val restoredItems: Int, val removedPlaceholders: Int)

    suspend fun run(): Result {
        val removed = removeLegacyPlaceholders()
        val restored = restoreIfEmpty()
        return Result(restored, removed)
    }

    /**
     * Restores from the newest valid snapshot when the database is empty.
     * A populated database is never overwritten: the local copy is always
     * fresher than a snapshot that is at most a day old.
     */
    private suspend fun restoreIfEmpty(): Int {
        val db = AppDb.get(context)
        val repo = OptionsRepo.get(context)
        if (db.items().all().isNotEmpty()) return 0

        val store = SnapshotStore(context, db, repo)
        val snapshot = store.readBestSnapshot() ?: return 0
        val imported = try {
            store.merge(snapshot)
        } catch (e: Exception) {
            AppLog.log(context, "recovery", "restore failed: ${e.message}")
            return 0
        }
        if (imported > 0) {
            // Runtime permissions and special access survive clear-data, so
            // onboarding only needs to show what is genuinely still missing.
            repo.setBool(OptionsRepo.K.ONBOARDING_DONE, true)
            AppLog.log(context, "recovery", "restored $imported items from a snapshot")
        }
        return imported
    }

    /**
     * Earlier builds parked a placeholder image in the output folder to stop
     * cloud apps forgetting it. The anchor rule replaced that, so any leftover
     * is deleted - it is app-owned, which means no user confirmation is needed.
     */
    private fun removeLegacyPlaceholders(): Int {
        val resolver = context.contentResolver
        var removed = 0
        for (volume in runCatching { MediaStore.getExternalVolumeNames(context) }
            .getOrDefault(setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val args = arrayOf(Defaults.OUTPUT_DIR_LIKE, "%\\_keep.jpg", context.packageName)
            try {
                val ids = mutableListOf<Long>()
                resolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null
                )?.use { c ->
                    while (c.moveToNext()) ids += c.getLong(0)
                }
                for (id in ids) {
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) {
                        removed++
                    }
                }
            } catch (e: Exception) {
                // Cleanup is best effort; the next launch tries again.
            }
        }
        if (removed > 0) {
            AppLog.log(context, "recovery", "removed $removed legacy placeholder(s)")
        }
        return removed
    }
}
