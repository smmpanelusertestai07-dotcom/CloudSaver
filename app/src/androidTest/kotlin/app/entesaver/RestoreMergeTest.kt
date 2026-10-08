package app.entesaver

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.entesaver.core.logic.Evidence
import app.entesaver.core.logic.ItemState
import app.entesaver.core.logic.OutFolder
import app.entesaver.core.logic.SnapshotCodec
import app.entesaver.data.db.AppDb
import app.entesaver.data.db.ItemRow
import app.entesaver.data.prefs.OptionsRepo
import app.entesaver.engine.SnapshotStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A restore picked by hand, merged into rows this install already has, and
 * the one-time repair of rows an older restore wrote.
 */
@RunWith(AndroidJUnit4::class)
class RestoreMergeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDb

    private fun row(
        fp: String,
        state: ItemState = ItemState.NEW,
        uri: String? = "content://media/external/images/media/42"
    ) = ItemRow(
        fingerprint = fp,
        displayName = "IMG_1.jpg",
        sizeBytes = 1_000,
        dateModified = 1_700_000_000,
        captureAt = 1_700_000_000_000,
        mimeType = "image/jpeg",
        isVideo = false,
        contentUri = uri,
        state = state.name
    )

    private fun history(
        fp: String,
        state: ItemState,
        evidence: Evidence,
        outputName: String = "IMG_1__0123456789abcdef.jpg",
        keptUri: String? = null
    ) = SnapshotCodec.SnapItem(
        fingerprint = fp,
        displayName = "IMG_1.jpg",
        sizeBytes = 1_000,
        dateModified = 1_700_000_000,
        captureAt = 1_700_000_000_000,
        mimeType = "image/jpeg",
        isVideo = false,
        state = state,
        evidence = evidence,
        goneReason = null,
        skipReason = null,
        outputName = outputName,
        outputBytes = 500L,
        outputSha256 = "aaaa",
        outputFolder = OutFolder.PHOTOS,
        releasedAt = 1_700_000_001_000,
        confirmedAt = 1_700_000_002_000,
        keptUri = keptUri
    )

    private fun merge(vararg items: SnapshotCodec.SnapItem) = runBlocking {
        val snapshot = SnapshotCodec.Snapshot(
            version = 1,
            exportedAt = 1_700_000_003_000,
            options = emptyMap(),
            items = items.toList(),
            batches = emptyList()
        )
        SnapshotStore(context, db, OptionsRepo.get(context)).merge(snapshot, importOptions = false)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
        // A restore is watched for a week; the tests after this one are not
        // about that (an old date, not none: an unset one is a restore made
        // by an earlier version, watched from its first pass).
        runBlocking { OptionsRepo.get(context).setLong(OptionsRepo.K.RESTORED_AT, 1L) }
    }

    @Test
    fun anOriginalEnteWasNeverProvenToHaveStaysInTheQueue() = runBlocking {
        db.items().insert(row("0123456789abcdef"))
        merge(history("0123456789abcdef", ItemState.RELEASED, Evidence.NONE))
        val after = db.items().byFingerprint("0123456789abcdef")!!
        assertEquals("parked UNKNOWN it would never be sent", ItemState.NEW.name, after.state)
    }

    @Test
    fun aCopyTheReattachPassAdoptedGetsItsHistory() = runBlocking {
        db.items().insert(
            row("0123456789abcdef", ItemState.RELEASED).copy(
                outputName = "IMG_1__0123456789abcdef.jpg",
                outputBytes = 500L,
                outputSha256 = null,
                evidence = Evidence.NONE.name
            )
        )
        merge(history("0123456789abcdef", ItemState.DONE, Evidence.CONFIRMED_EXACT))
        val after = db.items().byFingerprint("0123456789abcdef")!!
        assertEquals(Evidence.CONFIRMED_EXACT.name, after.evidence)
        assertEquals("lined up with the ledger", "aaaa", after.outputSha256)
    }

    @Test
    fun aLightCopyKeptInPlaceStaysTheKeptCopy() = runBlocking {
        // Re-keyed to the copy's own fingerprint; its upload copy's name
        // still carries the original's.
        db.items().insert(row("fedcba9876543210"))
        merge(
            history(
                "fedcba9876543210", ItemState.FREED_KEPT, Evidence.CONFIRMED_EXACT,
                keptUri = "content://media/external/images/media/7"
            )
        )
        val after = db.items().byFingerprint("fedcba9876543210")!!
        assertEquals(ItemState.FREED_KEPT.name, after.state)
        assertEquals("content://media/external/images/media/42", after.keptUri)
        assertTrue(
            "never offered to free as an original",
            db.items().reclaimCandidates().none { it.id == after.id }
        )
    }

    @Test
    fun theRepairSettlesWhatAnOlderRestoreLeftInTheQueue() = runBlocking {
        val now = 1_700_000_004_000
        db.items().insert(row("1111111111111111").copy(neverOptimise = true))
        db.items().insert(
            row("2222222222222222").copy(evidence = Evidence.CONFIRMED_EXACT.name, confirmedAt = 5)
        )
        db.items().insert(row("3333333333333333", uri = null).copy(fromImport = true))
        db.items().insert(row("4444444444444444"))
        db.items().parkWaitingExcluded(now)
        db.items().clearWaitingEvidence(now)
        db.items().deleteRestoredGhosts()

        val excluded = db.items().byFingerprint("1111111111111111")!!
        assertEquals(ItemState.SKIP.name, excluded.state)
        assertEquals("user_excluded", excluded.skipReason)
        val claimed = db.items().byFingerprint("2222222222222222")!!
        assertEquals(ItemState.NEW.name, claimed.state)
        assertEquals("a waiting row has no copy to prove", Evidence.NONE.name, claimed.evidence)
        assertNull(claimed.confirmedAt)
        assertNull("no scan could ever match it", db.items().byFingerprint("3333333333333333"))
        assertEquals(ItemState.NEW.name, db.items().byFingerprint("4444444444444444")!!.state)
    }
}
