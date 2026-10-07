package app.entesaver

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.entesaver.core.logic.Fingerprint
import app.entesaver.core.logic.GoneReason
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.KeptCopies
import app.entesaver.core.logic.ReclaimRules
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.engine.ReclaimEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bookkeeping after Android has removed the originals, driven directly.
 *
 * Android's dialog is not part of this: finish() is told which originals went,
 * exactly as the dialog's answer tells it, and the files are real MediaStore
 * entries so the identities it reads back are the ones a phone would give.
 */
@RunWith(AndroidJUnit4::class)
class ReclaimFinishTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*TestPermissions.forThisDevice())

    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDb.get(target)

    /** What MediaStore says a file is, as the scanner would fingerprint it. */
    private data class Seen(val name: String, val size: Long, val modified: Long) {
        val fingerprint get() = Fingerprint.fp16(name, size, modified)
    }

    @Before
    fun setUp() {
        MediaFixtures.cleanUp(target)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        MediaFixtures.cleanUp(target)
        runBlocking { db.clearAllTables() }
    }

    /**
     * The in-place copy lands beside its original under the same name, so the
     * provider calls it "name (1).jpg"; a scan in the window before finish()
     * made a NEW row for it under the very fingerprint finish() then gave the
     * original's row. The unique index refused, after the originals were
     * gone: a crash, no history, no undo, and the copy queued to be optimised
     * and uploaded again.
     */
    @Test
    fun anInPlaceCopyTheScannerMetFirstIsReKeyedWithoutACrash() = runBlocking {
        val original = photo("reclaim_inplace.jpg", seed = 1)
        val copy = photo("reclaim_inplace.jpg", seed = 2)
        val copySeen = seen(copy)
        assertTrue(
            "the copy must belong to the row under the provider's own name",
            KeptCopies.belongsTo(copySeen.name, "reclaim_inplace.jpg", "0000000000000000")
        )
        val rowId = db.items().insert(
            row("reclaim_inplace.jpg", original, "inplaceorig00001").copy(keptUri = copy.toString())
        )
        val strayId = db.items().insert(
            row(copySeen.name, copy, copySeen.fingerprint).copy(state = ItemState.NEW.name)
        )
        assertTrue(strayId > 0)

        val result = finish(rowId, original, copy, ReclaimRules.Mode.REPLACE_WITH_LIGHT)

        assertEquals(1, result.done.size)
        val freed = db.items().byId(rowId)!!
        assertEquals(ItemState.FREED_KEPT.name, freed.state)
        assertEquals(copySeen.fingerprint, freed.fingerprint)
        assertEquals(copy.toString(), freed.contentUri)
        assertNull("the scanner's stray row must be gone", db.items().byId(strayId))
        val history = db.reclaim().itemsOf(result.batchId)
        assertEquals("the batch must be in history, so it can be undone", 1, history.size)
        assertEquals(original.toString(), history.single().contentUri)
        assertEquals(freed.fingerprint, history.single().fingerprint)
    }

    /** A row that has been worked on keeps its history; this one keeps its key. */
    @Test
    fun aWorkedOnRowHoldingTheKeyIsLeftAlone() = runBlocking {
        val original = photo("reclaim_kept.jpg", seed = 3)
        val copy = photo("reclaim_kept.jpg", seed = 4)
        val copySeen = seen(copy)
        val rowId = db.items().insert(
            row("reclaim_kept.jpg", original, "keptorig00000001").copy(keptUri = copy.toString())
        )
        val otherId = db.items().insert(
            row(copySeen.name, copy, copySeen.fingerprint).copy(
                state = ItemState.RELEASED.name,
                releasedAt = 1L
            )
        )

        val result = finish(rowId, original, copy, ReclaimRules.Mode.REPLACE_WITH_LIGHT)

        assertEquals(1, result.done.size)
        assertNotNull(db.items().byId(otherId))
        val freed = db.items().byId(rowId)!!
        assertEquals(ItemState.FREED_KEPT.name, freed.state)
        assertEquals("keptorig00000001", freed.fingerprint)
        assertEquals(1, db.reclaim().itemsOf(result.batchId).size)
    }

    /**
     * Restoring an original freed in place must put its row back on the
     * original. Otherwise the history pointed at a fingerprint no row had,
     * nothing was updated, and the next scan queued the original as new.
     */
    @Test
    fun restoringAnInPlaceOriginalPointsItsRowBackAtIt() = runBlocking {
        val original = photo("reclaim_restore.jpg", seed = 5)
        val copy = photo("reclaim_restore.jpg", seed = 6)
        val rowId = db.items().insert(
            row("reclaim_restore.jpg", original, "restoreorig00001").copy(keptUri = copy.toString())
        )
        val result = finish(rowId, original, copy, ReclaimRules.Mode.REPLACE_WITH_LIGHT)
        val item = db.reclaim().itemsOf(result.batchId).single()

        ReclaimEngine(target).onRestored(item)

        val back = db.items().byId(rowId)!!
        assertEquals(ItemState.DONE.name, back.state)
        assertFalse(back.originalMissing)
        assertEquals(original.toString(), back.contentUri)
        assertEquals(seen(original).fingerprint, back.fingerprint)
        assertEquals(
            "the copy beside it must stay known to the scanner",
            copy.toString(), back.keptUri
        )
    }

    /**
     * Free up fully on a row whose copy is still in the upload folder: once
     * the row is FREED no folder pass reads it again, so the copy has to go
     * with the original, or it stays on the phone untracked forever.
     */
    @Test
    fun freeingAReleasedRowTakesItsUploadCopyWithIt() = runBlocking {
        val original = photo("reclaim_released.jpg", seed = 7)
        val uploadCopy = photo("reclaim_released__0123456789abcdef.jpg", seed = 8)
        val rowId = db.items().insert(
            row("reclaim_released.jpg", original, "releasedorig0001").copy(
                state = ItemState.RELEASED.name,
                outputUri = uploadCopy.toString(),
                outputBytes = 1234L,
                releasedAt = 1L
            )
        )

        val result = finish(rowId, original, null, ReclaimRules.Mode.FREE_UP_FULLY)

        assertEquals(1, result.done.size)
        val freed = db.items().byId(rowId)!!
        assertEquals(ItemState.FREED.name, freed.state)
        assertNull("the copy must not be left behind", freed.outputUri)
        assertEquals(GoneReason.APP_DELETED.name, freed.goneReason)
        assertFalse("the copy must be gone from the gallery", exists(uploadCopy))
        assertEquals(freed.sizeBytes + 1234L, result.freedBytes)
    }

    // ---- helpers ---------------------------------------------------------

    private fun photo(name: String, seed: Int): Uri =
        MediaFixtures.insertPhoto(target, name = name, width = 64, height = 64, seed = seed)

    private fun seen(uri: Uri): Seen =
        target.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            ),
            null, null, null
        )!!.use { c ->
            assertTrue("fixture $uri is not in MediaStore", c.moveToFirst())
            Seen(c.getString(0), c.getLong(1), c.getLong(2))
        }

    private fun exists(uri: Uri): Boolean =
        target.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null
        )?.use { it.count > 0 } ?: false

    private fun row(name: String, uri: Uri, fingerprint: String) = ItemRow(
        fingerprint = fingerprint,
        mediaStoreId = ContentUris.parseId(uri),
        contentUri = uri.toString(),
        displayName = name,
        sizeBytes = 3L * 1024 * 1024,
        dateModified = 1_600_000_000L,
        captureAt = 1_600_000_000_000L,
        mimeType = "image/jpeg",
        isVideo = false,
        bucket = "EnteSaverTest",
        state = ItemState.DONE.name,
        outputBytes = 1234L,
        updatedAt = 1L
    )

    /** finish() as the dialog's answer drives it: [original] went, [pinned] is the copy. */
    private suspend fun finish(
        rowId: Long,
        original: Uri,
        pinned: Uri?,
        mode: ReclaimRules.Mode
    ): ReclaimEngine.Result {
        val row = db.items().byId(rowId)!!
        val prepared = ReclaimEngine.Prepared(
            uris = listOf(original),
            rows = listOf(row),
            pinned = pinned?.let { mapOf(rowId to ReclaimEngine.Pinned(it, inPlace = true)) }
                ?: emptyMap(),
            skipped = emptyList()
        )
        return ReclaimEngine(target).finish(
            prepared, setOf(original.toString()), mode, trashed = true,
            now = System.currentTimeMillis()
        )
    }
}
