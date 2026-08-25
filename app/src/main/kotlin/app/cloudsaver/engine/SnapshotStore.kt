package app.cloudsaver.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.cloudsaver.R
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.GoneReason
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.core.logic.OutFolder
import app.cloudsaver.core.logic.SecureBackup
import app.cloudsaver.core.logic.SnapshotCodec
import app.cloudsaver.data.db.AppDb
import app.cloudsaver.data.db.BatchRow
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.data.db.LedgerRow
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.util.AppLog

/**
 * State durability. Room is the source of truth; these snapshots exist so the
 * app can recover after clear-data or a reinstall.
 *
 * Automatic snapshots are hidden - the app never leaves a visible file where
 * the user browses unless they tap Export. Targets are tried in order (beside
 * the output copies, a hidden dot-folder in Documents, a hidden dot-file, and
 * only then a visible file for devices that refuse every hidden option), and
 * writing continues past a target that fails so one refusal does not cost the
 * others.
 *
 * Items imported without upload evidence become UNKNOWN (never freed, never
 * reprocessed unless the user opts in).
 */
class SnapshotStore(
    private val context: Context,
    private val db: AppDb,
    private val optionsRepo: OptionsRepo
) {

    suspend fun build(): SnapshotCodec.Snapshot {
        val items = db.items().all().map { row ->
            SnapshotCodec.SnapItem(
                fingerprint = row.fingerprint,
                displayName = row.displayName,
                sizeBytes = row.sizeBytes,
                dateModified = row.dateModified,
                captureAt = row.captureAt,
                mimeType = row.mimeType,
                isVideo = row.isVideo,
                state = enumOr(row.state, ItemState.UNKNOWN),
                evidence = Evidence.parse(row.evidence),
                goneReason = enumOrNull<GoneReason>(row.goneReason),
                skipReason = row.skipReason,
                outputName = row.outputName,
                outputBytes = row.outputBytes,
                outputSha256 = row.outputSha256,
                outputFolder = enumOrNull<OutFolder>(row.outputFolder),
                releasedAt = row.releasedAt,
                confirmedAt = row.confirmedAt
            )
        }
        val batches = db.batches().all().map { b ->
            SnapshotCodec.SnapBatch(
                releasedAt = b.releasedAt,
                totalBytes = b.totalBytes,
                folder = enumOr(b.folder, OutFolder.SINGLE),
                cloudPackage = b.cloudPackage,
                verifiedAt = b.verifiedAt
            )
        }
        val ledger = db.ledger().all().map { l ->
            SnapshotCodec.SnapLedger(
                outputSha256 = l.outputSha256,
                fingerprint = l.fingerprint,
                displayName = l.displayName,
                outputBytes = l.outputBytes,
                evidence = Evidence.parse(l.evidence),
                confirmedAt = l.confirmedAt
            )
        }
        val access = app.cloudsaver.util.Permissions.mediaAccess(context).name
        return SnapshotCodec.Snapshot(
            version = SnapshotCodec.VERSION,
            exportedAt = System.currentTimeMillis(),
            options = optionsRepo.exportMap(),
            items = items,
            batches = batches,
            ledger = ledger,
            mediaAccess = access
        )
    }

    /**
     * Writes the safety snapshot to every target.
     *
     * Three copies, deliberately: two shared ones under Documents and
     * Download that survive an uninstall, and one inside the app's own files
     * directory that survives nothing but is always writable. Every target is
     * attempted - a snapshot that exists in one place only is one deletion
     * away from being no snapshot at all.
     *
     * Returns true when at least one shared copy was written. A total failure
     * of the shared copies is a Problem the user is told about, not a log
     * line nobody reads: it means an uninstall would lose their history.
     */
    suspend fun writeSafetySnapshot(): Boolean {
        val json = SnapshotCodec.encode(build())
        val failures = mutableListOf<String>()
        var wroteShared = false
        for ((dir, name) in Defaults.SNAPSHOT_TARGETS) {
            if (writeTo(json, dir, name)) wroteShared = true else failures += "$dir/$name"
        }
        // Always kept, whatever the shared writes did.
        writePrivate(json)

        if (!wroteShared) {
            ActivityLog(context).record(
                ActivityLog.Kind.PROBLEM,
                detail = context.getString(
                    R.string.problem_snapshot_failed, failures.joinToString(", ")
                )
            )
        }
        return wroteShared
    }

    /** The copy inside the app's own storage. Cheap, and always permitted. */
    private fun writePrivate(json: String): Boolean = try {
        java.io.File(context.filesDir, Defaults.SNAPSHOT_PRIVATE_NAME)
            .writeText(json, Charsets.UTF_8)
        true
    } catch (e: Exception) {
        AppLog.log(context, "snapshot", "private write failed: ${e.message}")
        false
    }

    private fun readPrivate(): String? = try {
        val file = java.io.File(context.filesDir, Defaults.SNAPSHOT_PRIVATE_NAME)
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Reads back the newest snapshot that passes its integrity check, trying
     * the targets in order. A corrupted or hand-edited copy is skipped rather
     * than trusted - it could otherwise promote evidence and put an original
     * in front of the user for deletion.
     */
    suspend fun readBestSnapshot(): SnapshotCodec.Snapshot? {
        var best: SnapshotCodec.Snapshot? = null
        fun consider(label: String, json: String?) {
            if (json == null) return
            val snapshot = try {
                SnapshotCodec.decode(json)
            } catch (e: Exception) {
                // A corrupted or hand-edited copy is skipped, never trusted:
                // it could otherwise promote evidence and put an original in
                // front of the user for deletion.
                AppLog.log(context, "snapshot", "ignoring $label: ${e.message}")
                return
            }
            if (best == null || snapshot.exportedAt > best!!.exportedAt) best = snapshot
        }
        for ((dir, name) in Defaults.SNAPSHOT_TARGETS + Defaults.LEGACY_SNAPSHOT_TARGETS) {
            consider("$dir/$name", readFrom(dir, name))
        }
        consider("app storage", readPrivate())
        return best
    }

    /** Locates an app-owned snapshot file, or null. */
    private fun findSnapshot(relativeDir: String, name: String): Uri? {
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(name, "$relativeDir/", context.packageName)
        return try {
            var found: Uri? = null
            resolver.query(
                files, arrayOf(MediaStore.MediaColumns._ID), selection, args, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    found = android.content.ContentUris.withAppendedId(files, c.getLong(0))
                }
            }
            found
        } catch (e: Exception) {
            null
        }
    }

    private fun readFrom(relativeDir: String, name: String): String? {
        val uri = findSnapshot(relativeDir, name) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Writes/updates [name] in [relativeDir] through MediaStore Files. */
    private fun writeTo(json: String, relativeDir: String, name: String): Boolean {
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            val target = findSnapshot(relativeDir, name) ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                }
                resolver.insert(files, values)
            } ?: return false
            resolver.openOutputStream(target, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            AppLog.log(context, "snapshot", "write to $relativeDir/$name failed: ${e.message}")
            false
        }
    }

    /**
     * Manual export to a user-chosen location. With a password the file is
     * encrypted on-device (AES-256-GCM); without one it is plain JSON.
     */
    suspend fun exportTo(uri: Uri, password: String?): Boolean = try {
        val json = SnapshotCodec.encode(build()).toByteArray(Charsets.UTF_8)
        val payload = if (password.isNullOrEmpty()) {
            json
        } else {
            SecureBackup.encrypt(json, password.toCharArray())
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(payload)
        } != null
    } catch (e: Exception) {
        AppLog.log(context, "snapshot", "export failed: ${e.message}")
        false
    }

    /** Outcome of an import attempt, so the UI can ask for a password. */
    sealed interface ImportResult {
        data class Success(val imported: Int) : ImportResult
        data object NeedsPassword : ImportResult
        data object WrongPassword : ImportResult
        data object Unreadable : ImportResult
    }

    /** Import from a chosen file; handles both plain and encrypted backups. */
    suspend fun importFrom(uri: Uri, password: String?): ImportResult {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ImportResult.Unreadable
        } catch (e: Exception) {
            return ImportResult.Unreadable
        }
        val json = if (SecureBackup.isEncrypted(bytes)) {
            if (password.isNullOrEmpty()) return ImportResult.NeedsPassword
            try {
                SecureBackup.decrypt(bytes, password.toCharArray()).toString(Charsets.UTF_8)
            } catch (e: SecureBackup.WrongPasswordException) {
                return ImportResult.WrongPassword
            } catch (e: Exception) {
                return ImportResult.Unreadable
            }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        return try {
            ImportResult.Success(merge(SnapshotCodec.decode(json)))
        } catch (e: Exception) {
            AppLog.log(context, "snapshot", "import failed: ${e.message}")
            ImportResult.Unreadable
        }
    }

    /** Merges a snapshot: never downgrades existing rows, applies UNKNOWN rules. */
    suspend fun merge(snapshot: SnapshotCodec.Snapshot): Int {
        // BB1.5: a snapshot exported under partial access is a fragment, not
        // an inventory. Merging stays safe because it only ever adds rows or
        // raises evidence - but the fact is logged, and the next scan (which
        // only runs under full access) fills in what the fragment lacks.
        if (snapshot.mediaAccess != "FULL") {
            AppLog.log(
                context, "snapshot",
                "imported snapshot was taken under ${snapshot.mediaAccess} access; " +
                    "treating as partial and rescanning"
            )
        }
        var imported = 0
        val now = System.currentTimeMillis()
        for (raw in snapshot.items) {
            val mapped = SnapshotCodec.applyImportMapping(raw)
            val existing = db.items().byFingerprint(mapped.fingerprint)
            if (existing == null) {
                val row = ItemRow(
                    fingerprint = mapped.fingerprint,
                    displayName = mapped.displayName,
                    sizeBytes = mapped.sizeBytes,
                    dateModified = mapped.dateModified,
                    captureAt = mapped.captureAt,
                    mimeType = mapped.mimeType,
                    isVideo = mapped.isVideo,
                    state = mapped.state.name,
                    evidence = mapped.evidence.name,
                    goneReason = raw.goneReason?.name,
                    skipReason = mapped.skipReason,
                    outputName = mapped.outputName,
                    outputBytes = mapped.outputBytes,
                    outputSha256 = mapped.outputSha256,
                    outputFolder = mapped.outputFolder?.name,
                    releasedAt = mapped.releasedAt,
                    confirmedAt = mapped.confirmedAt,
                    fromImport = true,
                    updatedAt = now
                )
                if (db.items().insert(row) != -1L) imported++
            } else {
                // Upgrade evidence only; never downgrade local knowledge.
                val existingEv = Evidence.parse(existing.evidence)
                if (mapped.evidence.ordinal > existingEv.ordinal) {
                    db.items().update(
                        existing.copy(
                            evidence = mapped.evidence.name,
                            confirmedAt = mapped.confirmedAt ?: existing.confirmedAt,
                            updatedAt = now
                        )
                    )
                }
            }
        }
        // The ledger goes in before anything else can act on it: a restored
        // install must know what the cloud already has before it decides
        // anything is missing.
        for (l in snapshot.ledger) {
            if (l.outputSha256.isEmpty()) continue
            db.ledger().insert(
                LedgerRow(
                    outputSha256 = l.outputSha256,
                    fingerprint = l.fingerprint,
                    displayName = l.displayName,
                    outputBytes = l.outputBytes,
                    evidence = l.evidence.name,
                    confirmedAt = l.confirmedAt
                )
            )
        }
        for (b in snapshot.batches) {
            db.batches().insert(
                BatchRow(
                    releasedAt = b.releasedAt,
                    totalBytes = b.totalBytes,
                    folder = b.folder.name,
                    cloudPackage = b.cloudPackage,
                    verifiedAt = b.verifiedAt
                )
            )
        }
        if (snapshot.options.isNotEmpty()) {
            optionsRepo.importMap(snapshot.options)
        }
        AppLog.log(context, "snapshot", "imported $imported items")
        return imported
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        enumOrNull<T>(value) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? {
        if (value.isNullOrEmpty()) return null
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
