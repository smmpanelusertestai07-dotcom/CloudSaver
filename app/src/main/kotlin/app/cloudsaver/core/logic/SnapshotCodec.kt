package app.cloudsaver.core.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON state snapshot: written daily to Documents/CloudSaver/state.json and via
 * manual Export (SAF). Survives clear-data / uninstall; on fresh install the
 * newest snapshot can be imported. Uses the platform org.json (no dependency).
 */
object SnapshotCodec {

    const val VERSION = 1

    data class SnapItem(
        val fingerprint: String,
        val displayName: String,
        val sizeBytes: Long,
        val dateModified: Long,
        val captureAt: Long,
        val mimeType: String,
        val isVideo: Boolean,
        val state: ItemState,
        val evidence: Evidence,
        val goneReason: GoneReason?,
        val skipReason: String?,
        val outputName: String?,
        val outputBytes: Long?,
        val outputSha256: String?,
        val outputFolder: OutFolder?,
        val releasedAt: Long?,
        val confirmedAt: Long?
    )

    data class SnapBatch(
        val releasedAt: Long,
        val totalBytes: Long,
        val folder: OutFolder,
        val cloudPackage: String?,
        val verifiedAt: Long?
    )

    data class Snapshot(
        val version: Int,
        val exportedAt: Long,
        val options: Map<String, String>,
        val items: List<SnapItem>,
        val batches: List<SnapBatch>
    )

    fun encode(snapshot: Snapshot): String {
        val root = JSONObject()
        root.put("app", "CloudSaver")
        root.put("version", snapshot.version)
        root.put("exportedAt", snapshot.exportedAt)
        val opts = JSONObject()
        for ((k, v) in snapshot.options) opts.put(k, v)
        root.put("options", opts)
        val items = JSONArray()
        for (i in snapshot.items) {
            val o = JSONObject()
            o.put("fp", i.fingerprint)
            o.put("name", i.displayName)
            o.put("size", i.sizeBytes)
            o.put("dmod", i.dateModified)
            o.put("cap", i.captureAt)
            o.put("mime", i.mimeType)
            o.put("video", i.isVideo)
            o.put("state", i.state.name)
            o.put("ev", i.evidence.name)
            i.goneReason?.let { o.put("gone", it.name) }
            i.skipReason?.let { o.put("skip", it) }
            i.outputName?.let { o.put("outName", it) }
            i.outputBytes?.let { o.put("outBytes", it) }
            i.outputSha256?.let { o.put("outSha", it) }
            i.outputFolder?.let { o.put("outFolder", it.name) }
            i.releasedAt?.let { o.put("relAt", it) }
            i.confirmedAt?.let { o.put("confAt", it) }
            items.put(o)
        }
        root.put("items", items)
        val batches = JSONArray()
        for (b in snapshot.batches) {
            val o = JSONObject()
            o.put("relAt", b.releasedAt)
            o.put("bytes", b.totalBytes)
            o.put("folder", b.folder.name)
            b.cloudPackage?.let { o.put("cloud", it) }
            b.verifiedAt?.let { o.put("verAt", it) }
            batches.put(o)
        }
        root.put("batches", batches)
        return root.toString()
    }

    fun decode(json: String): Snapshot {
        val root = JSONObject(json)
        val optionsObj = root.optJSONObject("options") ?: JSONObject()
        val options = mutableMapOf<String, String>()
        for (key in optionsObj.keys()) options[key] = optionsObj.optString(key, "")
        val items = mutableListOf<SnapItem>()
        val itemsArr = root.optJSONArray("items") ?: JSONArray()
        for (idx in 0 until itemsArr.length()) {
            val o = itemsArr.optJSONObject(idx) ?: continue
            val fp = o.optString("fp", "")
            if (fp.isEmpty()) continue
            items += SnapItem(
                fingerprint = fp,
                displayName = o.optString("name", ""),
                sizeBytes = o.optLong("size", 0),
                dateModified = o.optLong("dmod", 0),
                captureAt = o.optLong("cap", 0),
                mimeType = o.optString("mime", ""),
                isVideo = o.optBoolean("video", false),
                state = enumOr(o.optString("state"), ItemState.UNKNOWN),
                evidence = enumOr(o.optString("ev"), Evidence.NONE),
                goneReason = enumOrNull<GoneReason>(o.optString("gone", "")),
                skipReason = o.optString("skip", "").ifEmpty { null },
                outputName = o.optString("outName", "").ifEmpty { null },
                outputBytes = if (o.has("outBytes")) o.optLong("outBytes") else null,
                outputSha256 = o.optString("outSha", "").ifEmpty { null },
                outputFolder = enumOrNull<OutFolder>(o.optString("outFolder", "")),
                releasedAt = if (o.has("relAt")) o.optLong("relAt") else null,
                confirmedAt = if (o.has("confAt")) o.optLong("confAt") else null
            )
        }
        val batches = mutableListOf<SnapBatch>()
        val batchesArr = root.optJSONArray("batches") ?: JSONArray()
        for (idx in 0 until batchesArr.length()) {
            val o = batchesArr.optJSONObject(idx) ?: continue
            batches += SnapBatch(
                releasedAt = o.optLong("relAt", 0),
                totalBytes = o.optLong("bytes", 0),
                folder = enumOr(o.optString("folder"), OutFolder.SINGLE),
                cloudPackage = o.optString("cloud", "").ifEmpty { null },
                verifiedAt = if (o.has("verAt")) o.optLong("verAt") else null
            )
        }
        return Snapshot(
            version = root.optInt("version", VERSION),
            exportedAt = root.optLong("exportedAt", 0),
            options = options,
            items = items,
            batches = batches
        )
    }

    /** Applies the fresh-install import mapping to one snapshot item. */
    fun applyImportMapping(item: SnapItem): SnapItem {
        val (state, evidence) = StateMachine.importedState(item.state, item.evidence)
        return item.copy(state = state, evidence = evidence)
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
