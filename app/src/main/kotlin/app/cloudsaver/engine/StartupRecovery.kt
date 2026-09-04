package app.cloudsaver.engine

import android.content.Context
import android.provider.MediaStore
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.ScanSources
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.media.MediaScanner
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

    data class Result(
        val restoredItems: Int,
        val removedPlaceholders: Int,
        val removedLegacyFiles: Int,
        val purgedFromOutputFolders: Int
    )

    suspend fun run(): Result {
        // Read the old visible snapshot before deleting it: on an upgrade it
        // may be the only state left.
        val restored = restoreIfEmpty()
        val placeholders = removeLegacyPlaceholders()
        val legacy = removeLegacyVisibleSnapshot()
        val purged = purgeOutputFolderItems()
        return Result(restored, placeholders, legacy, purged)
    }

    /**
     * Older builds scanned every folder, so another pipeline's output - Ente's
     * upload folder, or this app's own copies under a previous name - could
     * already be queued for re-compression. Those rows are dropped.
     *
     * Only NEW and STAGED go: a RELEASED row carries upload evidence that the
     * user's Free-up decisions rest on, and that must not be rewritten here.
     */
    private suspend fun purgeOutputFolderItems(): Int {
        val db = AppDb.get(context)
        // Nothing queued means nothing this pass could purge. Ask that first:
        // the folder-reason scan below walks the whole gallery, tens of
        // thousands of rows on a full phone, and it ran at every single
        // launch even when the queue was empty - which is the normal state
        // once the backlog is through.
        val queued = db.items().countByState(ItemState.NEW.name) +
            db.items().countByState(ItemState.STAGED.name)
        if (queued == 0) return 0
        val scanner = MediaScanner(context, db)
        val excluded = runCatching { scanner.excludedBucketReasons() }.getOrNull() ?: return 0
        if (excluded.isEmpty()) return 0

        var purged = 0
        for (state in listOf(ItemState.NEW, ItemState.STAGED)) {
            for (row in db.items().byState(state.name)) {
                val bucket = row.bucket ?: continue
                if (bucket !in excluded) continue
                // A staged copy has a file behind it; drop that too.
                row.stagePath?.let { runCatching { java.io.File(it).delete() } }
                db.items().delete(row)
                purged++
            }
        }
        if (purged > 0) {
            // Keep the picker honest about it from now on.
            val repo = OptionsRepo.get(context)
            val current = repo.current().excludedBuckets
            repo.setStringSet(OptionsRepo.K.EXCLUDED_BUCKETS, current + excluded.keys)
            AppLog.log(
                context, "recovery",
                "purged $purged queued items from ${excluded.keys.joinToString()}"
            )
        }
        return purged
    }

    /**
     * Restores from the newest valid snapshot when the database is empty.
     * A populated database is never overwritten: the local copy is always
     * fresher than a snapshot that is at most a day old.
     */
    private suspend fun restoreIfEmpty(): Int {
        val db = AppDb.get(context)
        val repo = OptionsRepo.get(context)
        val o = repo.current()
        // Once per install, not "whenever the table is empty". The old
        // condition held true on every launch until the first file was
        // optimised, and each launch re-imported the snapshot's settings over
        // whatever the user had just chosen - a ticked album came back
        // unticked after every restart.
        if (o.restoreDone) return 0
        // A count, not the table: this runs at every launch, and a large
        // gallery means twenty thousand rows built only to be discarded.
        if (db.items().count() > 0) {
            repo.setBool(OptionsRepo.K.RESTORE_DONE, true)
            return 0
        }

        val store = SnapshotStore(context, db, repo)
        val snapshot = store.readBestSnapshot() ?: return 0
        // Settings come back only where nothing here has been chosen yet: a
        // fresh install after a reinstall, which is what restoring is for.
        // Someone already walking through setup keeps their own answers.
        val untouched = !o.onboardingDone && o.onboardingStep == 0
        val imported = try {
            store.merge(snapshot, importOptions = untouched)
        } catch (e: Exception) {
            AppLog.log(context, "recovery", "restore failed: ${e.message}")
            return 0
        }
        repo.setBool(OptionsRepo.K.RESTORE_DONE, true)
        if (imported > 0) {
            // Clear-data keeps runtime permissions, a reinstall does not. Only
            // skip setup when the app can actually see the gallery; otherwise
            // the restored install would land on Home unable to do anything,
            // with the one screen that asks for access already behind it.
            if (app.cloudsaver.util.Permissions.mediaAccess(context) ==
                app.cloudsaver.util.Permissions.MediaAccess.FULL
            ) {
                repo.setBool(OptionsRepo.K.ONBOARDING_DONE, true)
            }
            AppLog.log(context, "recovery", "restored $imported items from a snapshot")
        }
        return imported
    }

    /**
     * Earlier builds wrote the automatic snapshot to a visible
     * Documents/CloudSaver folder, which is exactly where someone browsing
     * Files finds a folder they never created. Snapshots are hidden now, so
     * the old visible one is removed once its contents have been read.
     */
    private fun removeLegacyVisibleSnapshot(): Int {
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(
            "${Defaults.SNAPSHOT_DIR_LEGACY_VISIBLE}/",
            Defaults.SNAPSHOT_NAME,
            context.packageName
        )
        return try {
            val ids = mutableListOf<Long>()
            resolver.query(
                files, arrayOf(MediaStore.MediaColumns._ID), selection, args, null
            )?.use { c ->
                while (c.moveToNext()) ids += c.getLong(0)
            }
            var removed = 0
            for (id in ids) {
                val uri = android.content.ContentUris.withAppendedId(files, id)
                if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) {
                    removed++
                }
            }
            if (removed > 0) {
                AppLog.log(context, "recovery", "removed the old visible snapshot")
            }
            removed
        } catch (e: Exception) {
            0
        }
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
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val args = arrayOf(Defaults.OUTPUT_DIR_LIKE, context.packageName)
            try {
                val ids = mutableListOf<Long>()
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    selection,
                    args,
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        // The name is judged in Kotlin. Asking SQL for it needs
                        // an ESCAPE clause to keep the underscore literal, and
                        // the query that did not have one matched nothing.
                        if (ScanSources.isKeepPlaceholderName(c.getString(1))) ids += c.getLong(0)
                    }
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
