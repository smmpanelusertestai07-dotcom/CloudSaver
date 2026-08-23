package app.cloudsaver.core.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON state snapshot: written daily to hidden locations and via manual Export
 * (SAF). Survives clear-data / uninstall; on fresh install the newest valid
 * snapshot can be imported. Uses the platform org.json (no dependency).
 *
 * Every snapshot carries a schema version and a SHA-256 of its own payload, so
 * a hand-edited or truncated file is rejected rather than trusted - a snapshot
 * can promote an item's evidence, and evidence is what offers an original for
 * deletion. It holds names, dates, sizes and hashes only; never media content.
 */
object SnapshotCodec {

    /** Bumped when the payload shape changes; older versions are still read. */
    const val VERSION = 3

    /** Oldest schema this build understands. */
    const val MIN_SUPPORTED_VERSION = 1

    private const val KEY_PAYLOAD = "payload"
    private const val KEY_INTEGRITY = "sha256"

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

    /**
     * One delivered copy, remembered for good.
     *
     * The ledger is what stops the app re-uploading a photo the cloud already
     * has - and a reinstall must not undo that, or the first sync after one
     * would fill the account it was meant to save. So it travels in the
     * snapshot alongside the items.
     */
    data class SnapLedger(
        val outputSha256: String,
        val fingerprint: String,
        val displayName: String,
        val outputBytes: Long,
        val evidence: Evidence,
        val confirmedAt: Long
    )

    data class Snapshot(
        val version: Int,
        val exportedAt: Long,
        val options: Map<String, String>,
        val items: List<SnapItem>,
        val batches: List<SnapBatch>,
        val ledger: List<SnapLedger> = emptyList()
    )

    /** Envelope: {"app","schemaVersion","sha256","payload":{...}}. */
    fun encode(snapshot: Snapshot): String {
        val payload = encodePayload(snapshot)
        return JSONObject().apply {
            put("app", "CloudSaver")
            put("schemaVersion", snapshot.version)
            put(KEY_INTEGRITY, sha256Hex(payload))
            put(KEY_PAYLOAD, JSONObject(payload))
        }.toString()
    }

    private fun encodePayload(snapshot: Snapshot): String {
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
        val ledger = JSONArray()
        for (l in snapshot.ledger) {
            val o = JSONObject()
            o.put("sha", l.outputSha256)
            o.put("fp", l.fingerprint)
            o.put("name", l.displayName)
            o.put("bytes", l.outputBytes)
            o.put("ev", l.evidence.name)
            o.put("at", l.confirmedAt)
            ledger.put(o)
        }
        root.put("ledger", ledger)
        return root.toString()
    }

    /** Thrown when a snapshot is unreadable, too new, or fails its hash. */
    class InvalidSnapshotException(message: String) : Exception(message)

    /**
     * Reads a snapshot, verifying the schema version and the integrity hash.
     * Version 1 files predate the envelope and are accepted as-is; from
     * version 2 on, a payload whose hash does not match is refused.
     */
    fun decode(json: String): Snapshot {
        val outer = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidSnapshotException("not JSON")
        }
        if (!outer.has(KEY_PAYLOAD)) {
            // Version 1: the payload was the whole document.
            return decodePayload(outer)
        }
        val schema = outer.optInt("schemaVersion", VERSION)
        if (schema < MIN_SUPPORTED_VERSION) {
            throw InvalidSnapshotException("schema $schema is too old")
        }
        if (schema > VERSION) {
            throw InvalidSnapshotException("schema $schema is newer than $VERSION")
        }
        val payload = outer.optJSONObject(KEY_PAYLOAD)
            ?: throw InvalidSnapshotException("no payload")
        val expected = outer.optString(KEY_INTEGRITY, "")
        val actual = sha256Hex(payload.toString())
        if (expected.isEmpty() || !expected.equals(actual, ignoreCase = true)) {
            throw InvalidSnapshotException("integrity hash mismatch")
        }
        return decodePayload(payload)
    }

    private fun decodePayload(root: JSONObject): Snapshot {
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
                evidence = Evidence.parse(o.optString("ev")),
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
        val ledger = mutableListOf<SnapLedger>()
        val ledgerArr = root.optJSONArray("ledger") ?: JSONArray()
        for (idx in 0 until ledgerArr.length()) {
            val o = ledgerArr.optJSONObject(idx) ?: continue
            val sha = o.optString("sha", "")
            if (sha.isEmpty()) continue
            ledger += SnapLedger(
                outputSha256 = sha,
                fingerprint = o.optString("fp", ""),
                displayName = o.optString("name", ""),
                outputBytes = o.optLong("bytes", 0),
                evidence = Evidence.parse(o.optString("ev", "")),
                confirmedAt = o.optLong("at", 0)
            )
        }
        return Snapshot(
            version = root.optInt("version", VERSION),
            exportedAt = root.optLong("exportedAt", 0),
            options = options,
            items = items,
            batches = batches,
            ledger = ledger
        )
    }

    fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) append("%02x".format(b))
        }
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
