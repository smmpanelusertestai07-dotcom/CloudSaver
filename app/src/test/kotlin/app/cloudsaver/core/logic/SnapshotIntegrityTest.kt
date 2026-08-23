package app.cloudsaver.core.logic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A snapshot can raise an item's evidence, and evidence is what puts an
 * original in front of the user for deletion, so an edited one must be
 * refused rather than trusted.
 */
class SnapshotIntegrityTest {

    private fun snapshot(exportedAt: Long = 1_700_000_000_000L) = SnapshotCodec.Snapshot(
        version = SnapshotCodec.VERSION,
        exportedAt = exportedAt,
        options = mapOf("preset" to "STORAGE_SAVER", "dailyCapMb" to "250"),
        items = listOf(
            SnapshotCodec.SnapItem(
                fingerprint = "abc123",
                displayName = "IMG_0001.jpg",
                sizeBytes = 4_000_000,
                dateModified = 1_699_000_000,
                captureAt = 1_699_000_000_000,
                mimeType = "image/jpeg",
                isVideo = false,
                state = ItemState.RELEASED,
                evidence = Evidence.VERIFIED,
                goneReason = null,
                skipReason = null,
                outputName = "IMG_0001_cs.jpg",
                outputBytes = 900_000,
                outputSha256 = "deadbeef",
                outputFolder = OutFolder.SINGLE,
                releasedAt = 1_699_500_000_000,
                confirmedAt = null
            )
        ),
        batches = listOf(
            SnapshotCodec.SnapBatch(
                releasedAt = 1_699_500_000_000,
                totalBytes = 900_000,
                folder = OutFolder.SINGLE,
                cloudPackage = "io.ente.photos",
                verifiedAt = null
            )
        )
    )

    @Test
    fun roundTrip() {
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot()))
        assertEquals(1, decoded.items.size)
        assertEquals("abc123", decoded.items[0].fingerprint)
        assertEquals(Evidence.VERIFIED, decoded.items[0].evidence)
        assertEquals(1, decoded.batches.size)
        assertEquals("250", decoded.options["dailyCapMb"])
        assertEquals(1_700_000_000_000L, decoded.exportedAt)
    }

    @Test
    fun envelopeCarriesSchemaAndHash() {
        val root = JSONObject(SnapshotCodec.encode(snapshot()))
        assertEquals("CloudSaver", root.getString("app"))
        assertEquals(SnapshotCodec.VERSION, root.getInt("schemaVersion"))
        assertEquals(64, root.getString("sha256").length)
        assertTrue(root.has("payload"))
    }

    @Test(expected = SnapshotCodec.InvalidSnapshotException::class)
    fun editedPayloadIsRejected() {
        val root = JSONObject(SnapshotCodec.encode(snapshot()))
        // Someone raises an item's evidence by hand to unlock deletion.
        root.getJSONObject("payload").getJSONArray("items").getJSONObject(0)
            .put("ev", Evidence.CONFIRMED.name)
        SnapshotCodec.decode(root.toString())
    }

    @Test(expected = SnapshotCodec.InvalidSnapshotException::class)
    fun strippedHashIsRejected() {
        val root = JSONObject(SnapshotCodec.encode(snapshot()))
        root.remove("sha256")
        SnapshotCodec.decode(root.toString())
    }

    @Test(expected = SnapshotCodec.InvalidSnapshotException::class)
    fun futureSchemaIsRejected() {
        val root = JSONObject(SnapshotCodec.encode(snapshot()))
        root.put("schemaVersion", SnapshotCodec.VERSION + 1)
        SnapshotCodec.decode(root.toString())
    }

    @Test(expected = SnapshotCodec.InvalidSnapshotException::class)
    fun garbageIsRejected() {
        SnapshotCodec.decode("this is not json at all")
    }

    /** Version 1 files predate the envelope; they must still be readable. */
    @Test
    fun legacyUnwrappedSnapshotStillReads() {
        val payload = JSONObject(SnapshotCodec.encode(snapshot())).getJSONObject("payload")
        val decoded = SnapshotCodec.decode(payload.toString())
        assertEquals(1, decoded.items.size)
        assertEquals("abc123", decoded.items[0].fingerprint)
    }

    @Test
    fun hashChangesWithContent() {
        val a = JSONObject(SnapshotCodec.encode(snapshot(1L))).getString("sha256")
        val b = JSONObject(SnapshotCodec.encode(snapshot(2L))).getString("sha256")
        assertNotEquals(a, b)
    }

    @Test
    fun snapshotCarriesNoMediaContent() {
        val json = SnapshotCodec.encode(snapshot())
        // Names, dates, sizes and hashes only - never bytes of a photo.
        assertFalse(json.contains("base64"))
        assertFalse(json.contains("data:image"))
        assertTrue(json.contains("IMG_0001.jpg"))
    }
}
