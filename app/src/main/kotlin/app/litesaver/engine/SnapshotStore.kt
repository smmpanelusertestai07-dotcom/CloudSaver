package app.litesaver.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.litesaver.core.logic.Defaults
import app.litesaver.core.logic.Evidence
import app.litesaver.core.logic.GoneReason
import app.litesaver.core.logic.ItemState
import app.litesaver.core.logic.OutFolder
import app.litesaver.core.logic.SnapshotCodec
import app.litesaver.data.db.AppDb
import app.litesaver.data.db.BatchRow
import app.litesaver.data.db.ItemRow
import app.litesaver.data.prefs.OptionsRepo
import app.litesaver.util.LiteLog

/**
 * State durability: daily JSON snapshot to Documents/LiteSaver/state.json,
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

    /** Writes/updates Documents/LiteSaver/state.json through MediaStore Files. */
    suspend fun writeDocumentsSnapshot(): Boolean {
        val json = SnapshotCodec.encode(build())
        val resolver = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return try {
            var target: Uri? = null
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            val args = arrayOf(
                Defaults.SNAPSHOT_NAME,
                "${Defaults.DOCS_DIR}/",
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
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Defaults.DOCS_DIR}/")
                }
                target = resolver.insert(files, values)
            }
            val uri = target ?: return false
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return false
            LiteLog.log(context, "snapshot", "wrote ${Defaults.DOCS_DIR}/${Defaults.SNAPSHOT_NAME}")
            true
        } catch (e: Exception) {
            LiteLog.log(context, "snapshot", "write failed: ${e.message}")
            false
        }
    }

    /** Manual export to a user-chosen SAF location (e.g. a cloud drive folder). */
    suspend fun exportTo(uri: Uri): Boolean = try {
        val json = SnapshotCodec.encode(build())
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        } != null
    } catch (e: Exception) {
        LiteLog.log(context, "snapshot", "export failed: ${e.message}")
        false
    }

    /** Import from SAF. Returns number of imported (new) items, or -1 on failure. */
    suspend fun importFrom(uri: Uri): Int {
        val json = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return -1
        } catch (e: Exception) {
            return -1
        }
        return try {
            merge(SnapshotCodec.decode(json))
        } catch (e: Exception) {
            LiteLog.log(context, "snapshot", "import failed: ${e.message}")
            -1
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
        LiteLog.log(context, "snapshot", "imported $imported items")
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
