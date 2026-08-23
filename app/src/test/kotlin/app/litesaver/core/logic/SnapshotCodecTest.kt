package app.litesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCodecTest {

    private fun item(
        fp: String,
        state: ItemState,
        evidence: Evidence,
        outputName: String? = "a__$fp.jpg"
    ) = SnapshotCodec.SnapItem(
        fingerprint = fp,
        displayName = "IMG_$fp.jpg",
        sizeBytes = 1000,
        dateModified = 1700000000,
        captureAt = 1700000000000,
        mimeType = "image/jpeg",
        isVideo = false,
        state = state,
        evidence = evidence,
        goneReason = null,
        skipReason = null,
        outputName = outputName,
        outputBytes = 500,
        outputSha256 = "beef",
        outputFolder = OutFolder.SINGLE,
        releasedAt = 1700000001000,
        confirmedAt = null
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val snapshot = SnapshotCodec.Snapshot(
            version = SnapshotCodec.VERSION,
            exportedAt = 42,
            options = mapOf("preset" to "STORAGE_SAVER", "dailyCapMb" to "250"),
            items = listOf(
                item("aaaaaaaaaaaaaaaa", ItemState.RELEASED, Evidence.VERIFIED),
                item("bbbbbbbbbbbbbbbb", ItemState.DONE, Evidence.CONFIRMED)
            ),
            batches = listOf(
                SnapshotCodec.SnapBatch(10, 500, OutFolder.SINGLE, "io.ente.photos", null)
            )
        )
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
        assertEquals(snapshot.version, decoded.version)
        assertEquals(snapshot.exportedAt, decoded.exportedAt)
        assertEquals(snapshot.options, decoded.options)
        assertEquals(snapshot.items, decoded.items)
        assertEquals(snapshot.batches, decoded.batches)
    }

    @Test
    fun importMappingReleasedWithoutEvidenceBecomesUnknown() {
        val mapped = SnapshotCodec.applyImportMapping(
            item("cccccccccccccccc", ItemState.RELEASED, Evidence.NONE)
        )
        assertEquals(ItemState.UNKNOWN, mapped.state)
        assertEquals(Evidence.NONE, mapped.evidence)
    }

    @Test
    fun importMappingKeepsConfirmed() {
        val mapped = SnapshotCodec.applyImportMapping(
            item("dddddddddddddddd", ItemState.RELEASED, Evidence.CONFIRMED)
        )
        assertEquals(ItemState.DONE, mapped.state)
        assertEquals(Evidence.CONFIRMED, mapped.evidence)
    }

    @Test
    fun importMappingStagedRestartsAsNew() {
        val mapped = SnapshotCodec.applyImportMapping(
            item("eeeeeeeeeeeeeeee", ItemState.STAGED, Evidence.NONE)
        )
        assertEquals(ItemState.NEW, mapped.state)
    }

    @Test
    fun decodeToleratesMissingFields() {
        val decoded = SnapshotCodec.decode("""{"version":1}""")
        assertTrue(decoded.items.isEmpty())
        assertTrue(decoded.batches.isEmpty())
        assertTrue(decoded.options.isEmpty())
    }

    @Test
    fun decodeSkipsItemsWithoutFingerprintAndBadEnums() {
        val json = """
            {"version":1,"items":[
                {"name":"no-fp.jpg","size":10},
                {"fp":"ffffffffffffffff","name":"x.jpg","size":10,"state":"GARBAGE","ev":"???"}
            ]}
        """.trimIndent()
        val decoded = SnapshotCodec.decode(json)
        assertEquals(1, decoded.items.size)
        assertEquals(ItemState.UNKNOWN, decoded.items[0].state)
        assertEquals(Evidence.NONE, decoded.items[0].evidence)
    }
}
