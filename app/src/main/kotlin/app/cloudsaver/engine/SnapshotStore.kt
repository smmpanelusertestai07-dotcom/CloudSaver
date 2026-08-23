package app.cloudsaver.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.util.AppLog

/**
 * State durability: daily JSON snapshot to Documents/CloudSaver/state.json,
 * manual Export/Import via SAF, plus Android Auto Backup of the Room DB.
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
                evidence = enumOr(row.evidence, Evidence.NONE),
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
        return SnapshotCodec.Snapshot(
            version = SnapshotCodec.VERSION,
            exportedAt = System.currentTimeMillis(),
            options = optionsRepo.exportMap(),
            items = items,
            batches = batches
        )
    }

    /**
     * Daily snapshot to Documents/CloudSaver/state.json plus a hidden copy in
     * Pictures/CloudSaver/.cloudsaver/state.json, so the state also travels with
     * the output folder itself. Returns true when at least one copy was written.
     */
    suspend fun writeDocumentsSnapshot(): Boolean {
        val json = SnapshotCodec.encode(build())
        val docs = writeTo(json, Defaults.DOCS_DIR)
        // Best effort: some OEM MediaStore builds reject dot-directories.
        val hidden = writeTo(json, Defaults.HIDDEN_SNAPSHOT_DIR)
        return docs || hidden
    }

    /** Writes/updates state.json in [relativeDir] through MediaStore Files. */
    private fun writeTo(json: String, relativeDir: String): Boolean {
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            var target: Uri? = null
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val args = arrayOf(
                Defaults.SNAPSHOT_NAME,
                "$relativeDir/",
                context.packageName
            )
            resolver.query(
                files, arrayOf(MediaStore.MediaColumns._ID), selection, args, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    target = android.content.ContentUris.withAppendedId(files, c.getLong(0))
                }
            }
            if (target == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, Defaults.SNAPSHOT_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativeDir/")
                }
                target = resolver.insert(files, values)
            }
            val uri = target ?: return false
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return false
            AppLog.log(context, "snapshot", "wrote $relativeDir/${Defaults.SNAPSHOT_NAME}")
            true
        } catch (e: Exception) {
            AppLog.log(context, "snapshot", "write to $relativeDir failed: ${e.message}")
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
                val existingEv = enumOr(existing.evidence, Evidence.NONE)
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
