package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportMergeTest {

    private fun item(
        state: ItemState,
        evidence: Evidence = Evidence.NONE,
        sha: String? = "aaaa"
    ) = SnapshotCodec.applyImportMapping(
        SnapshotCodec.SnapItem(
            fingerprint = "0123456789abcdef",
            displayName = "IMG_1.jpg",
            sizeBytes = 1000,
            dateModified = 1700000000,
            captureAt = 1700000000000,
            mimeType = "image/jpeg",
            isVideo = false,
            state = state,
            evidence = evidence,
            goneReason = null,
            skipReason = null,
            outputName = sha?.let { "IMG_1__0123456789abcdef.jpg" },
            outputBytes = sha?.let { 500L },
            outputSha256 = sha,
            outputFolder = OutFolder.PHOTOS,
            releasedAt = sha?.let { 1700000001000 },
            confirmedAt = null
        )
    )

    /** The history's record as written, before the import mapping. */
    private fun raw(
        state: ItemState,
        releasedAt: Long?,
        leftAt: Long?,
        evidence: Evidence = Evidence.VERIFIED
    ) = SnapshotCodec.SnapItem(
        fingerprint = "0123456789abcdef",
        displayName = "IMG_1.jpg",
        sizeBytes = 1000,
        dateModified = 1700000000,
        captureAt = 1700000000000,
        mimeType = "image/jpeg",
        isVideo = false,
        state = state,
        evidence = evidence,
        goneReason = null,
        skipReason = null,
        outputName = "IMG_1__0123456789abcdef.jpg",
        outputBytes = 500L,
        outputSha256 = "aaaa",
        outputFolder = OutFolder.PHOTOS,
        releasedAt = releasedAt,
        confirmedAt = null,
        leftFolderAt = leftAt
    )

    private fun scanned(state: ItemState = ItemState.NEW, sha: String? = null, never: Boolean = false) =
        ImportMerge.Local(state, Evidence.NONE, sha, never)

    @Test
    fun `a scanned row takes the history of a copy Ente already has, not just its evidence`() {
        // Evidence alone left the row queued: it was encoded again, the new
        // copy went up as a second one, and having "evidence" it was never
        // watched or put in the ledger.
        val history = item(ItemState.DONE, Evidence.CONFIRMED_EXACT)
        val plan = ImportMerge.plan(scanned(), history)
        assertTrue(plan.takeOver)
        assertFalse(plan.raiseEvidence)
        assertEquals(ItemState.DONE, ImportMerge.takenOverState(history))
    }

    @Test
    fun `a staged row is taken over too, and a proven copy still waiting comes back for the watch`() {
        assertTrue(ImportMerge.plan(scanned(ItemState.STAGED, "bbbb"), item(ItemState.DONE, Evidence.VERIFIED)).takeOver)
        val waiting = item(ItemState.RELEASED, Evidence.VERIFIED)
        assertEquals(ItemState.UNKNOWN, waiting.state)
        assertTrue(ImportMerge.plan(scanned(), waiting).takeOver)
        assertEquals(ItemState.UNKNOWN, ImportMerge.takenOverState(waiting))
    }

    @Test
    fun `an original whose copy Ente was never proven to have stays in the queue`() {
        // Taken over, the row sat UNKNOWN (no evidence) or DONE (aged) with
        // its original never sent again, after a phone move that left the
        // copy behind. In the queue it is sent; the ledger still stops a
        // byte-identical copy going up twice.
        for (evidence in listOf(Evidence.NONE, Evidence.AGED)) {
            for (state in listOf(ItemState.RELEASED, ItemState.DONE, ItemState.GONE, ItemState.FREED)) {
                val plan = ImportMerge.plan(scanned(), item(state, evidence))
                assertFalse("$state/$evidence", plan.takeOver)
                assertFalse("$state/$evidence", plan.changes)
            }
        }
        assertTrue(ImportMerge.plan(scanned(), item(ItemState.DONE, Evidence.CONFIRMED_PACED)).takeOver)
    }

    @Test
    fun `a copy the reattach pass adopted gets the history of that same copy`() {
        // Adopted from the folder, it has the file's name and size but no
        // hash, so the hash test alone never matched it: it waited for
        // traffic Ente, which had it already, would never send.
        val history = item(ItemState.DONE, Evidence.CONFIRMED_EXACT)
        val adopted = ImportMerge.Local(
            ItemState.RELEASED, Evidence.NONE, null, false,
            outputName = "IMG_1__0123456789abcdef.jpg", outputBytes = 500L
        )
        assertTrue(ImportMerge.sameCopy(adopted, history))
        assertTrue(ImportMerge.plan(adopted, history).raiseEvidence)
        assertFalse("another size is another copy", ImportMerge.plan(adopted.copy(outputBytes = 501L), history).raiseEvidence)
        assertFalse(ImportMerge.plan(adopted.copy(outputName = "IMG_1__0123456789abcdef (1).jpg"), history).raiseEvidence)
        assertFalse("only a released copy is matched by name", ImportMerge.sameCopy(adopted.copy(state = ItemState.DONE), history))
        assertFalse("a hash, when there is one, decides", ImportMerge.sameCopy(adopted.copy(outputSha256 = "bbbb"), history))
    }

    @Test
    fun `a light copy kept in place is still the kept copy, never an original to free`() {
        // In place, the row was re-keyed to the copy's own fingerprint, so a
        // scan after a reinstall meets the copy under it. Taken over as DONE,
        // the person's only local version was offered to free as an original.
        val inPlace = item(ItemState.FREED_KEPT, Evidence.CONFIRMED_EXACT).copy(
            fingerprint = "fedcba9876543210",
            keptUri = "content://media/external/images/media/7"
        )
        assertTrue(ImportMerge.scannedIsKeptCopy(inPlace))
        assertTrue(ImportMerge.plan(scanned(), inPlace).takeOver)
        assertEquals(ItemState.FREED_KEPT, ImportMerge.takenOverState(inPlace))
        assertEquals(
            "this phone's address for the file, not the old phone's",
            "content://media/external/images/media/42",
            ImportMerge.keptUriAfterTakeOver(inPlace, "content://media/external/images/media/42")
        )
        // A copy in its own album leaves the row on the original's
        // fingerprint: what the scan found is the original, found again.
        val ownAlbum = item(ItemState.FREED_KEPT, Evidence.CONFIRMED_EXACT).copy(
            keptUri = "content://media/external/images/media/7"
        )
        assertFalse(ImportMerge.scannedIsKeptCopy(ownAlbum))
        assertEquals(ItemState.DONE, ImportMerge.takenOverState(ownAlbum))
        assertEquals(
            "and the kept copy stays tracked",
            ownAlbum.keptUri,
            ImportMerge.keptUriAfterTakeOver(ownAlbum, "content://media/external/images/media/42")
        )
    }

    @Test
    fun `a reclaimed original found again is DONE, and only with proof`() {
        assertEquals(ItemState.DONE, ImportMerge.takenOverState(item(ItemState.FREED, Evidence.CONFIRMED_EXACT)))
        assertEquals(ItemState.UNKNOWN, ImportMerge.takenOverState(item(ItemState.FREED_KEPT)))
    }

    @Test
    fun `evidence never moves onto a different copy`() {
        val history = item(ItemState.DONE, Evidence.CONFIRMED_EXACT, sha = "aaaa")
        val other = ImportMerge.plan(scanned(ItemState.RELEASED, "bbbb"), history)
        assertFalse(other.takeOver)
        assertFalse("a re-encode already released keeps its own, unproven, evidence", other.raiseEvidence)
        assertFalse(other.changes)
        val same = ImportMerge.plan(scanned(ItemState.RELEASED, "aaaa"), history)
        assertTrue(same.raiseEvidence)
        assertFalse(ImportMerge.plan(scanned(ItemState.RELEASED, null), history).raiseEvidence)
    }

    @Test
    fun `a restored never-optimise parks a waiting row, as the person's own choice did`() {
        val excluded = item(ItemState.SKIP, sha = null).copy(neverOptimise = true, skipReason = "user_excluded")
        val waiting = ImportMerge.plan(scanned(), excluded)
        assertTrue(waiting.exclude)
        assertTrue(waiting.addNeverFlag)
        assertTrue(ImportMerge.plan(scanned(ItemState.STAGED, "bbbb"), excluded).exclude)
        // A copy already released cannot be taken back; the flag is all.
        val released = ImportMerge.plan(scanned(ItemState.RELEASED, "bbbb"), excluded)
        assertFalse(released.exclude)
        assertTrue(released.addNeverFlag)
        assertFalse(ImportMerge.plan(scanned(never = true), excluded).changes)
    }

    @Test
    fun `queue rows are left to the scan instead of restored as ghosts`() {
        assertNull(ImportMerge.forInsert(item(ItemState.NEW, sha = null)))
        assertNull("a staged copy went with the old app data", ImportMerge.forInsert(item(ItemState.STAGED)))
        val kept = item(ItemState.NEW, sha = null).copy(keptUri = "content://media/external/images/media/9")
        assertEquals(kept, ImportMerge.forInsert(kept))
        val released = item(ItemState.RELEASED)
        assertEquals(released, ImportMerge.forInsert(released))
    }

    @Test
    fun `a duplicate comes back only with the original it belongs to`() {
        val dup = item(ItemState.SKIP, sha = null).copy(skipReason = "duplicate")
        assertNull("without its link it only ever read as a problem", ImportMerge.forInsert(dup))
        val linked = dup.copy(duplicateOf = "fedcba9876543210")
        assertEquals(linked, ImportMerge.forInsert(linked))
    }

    @Test
    fun `a waiting row the person excluded is inserted excluded`() {
        val never = item(ItemState.NEW, sha = null).copy(neverOptimise = true)
        val row = ImportMerge.forInsert(never)!!
        assertEquals(ItemState.SKIP, row.state)
        assertEquals(ImportMerge.USER_EXCLUDED, row.skipReason)
    }

    @Test
    fun aCopyTheHistorySawWaitingCarriesNoLeaveTime() {
        // Re-released after an earlier leave, or restored and not yet found:
        // the copy may still be in the folder, so a recorded leave is stale.
        val now = 50_000L
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.RELEASED, 30_000L, 20_000L), now))
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.UNKNOWN, 30_000L, 20_000L), now))
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.RELEASED, 30_000L, null), now))
        // Gone or done without proof is restored UNKNOWN too: the reattach
        // pass may yet find its copy in this phone's folder.
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.GONE, 10_000L, 20_000L, Evidence.NONE), now))
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.DONE, 10_000L, 20_000L, Evidence.NONE), now))
    }

    @Test
    fun aCopyThatHadLeftKeepsItsLeaveOrIsDatedAtTheImport() {
        val now = 50_000L
        // Recorded: kept, even with the release time cleared (sent back).
        assertEquals(20_000L, ImportMerge.leftAtOnImport(raw(ItemState.GONE, 10_000L, 20_000L), now))
        assertEquals(20_000L, ImportMerge.leftAtOnImport(raw(ItemState.NEW, null, 20_000L), now))
        // From a history written before 12.2: the import, never earlier than
        // it left, and fixed from then on.
        assertEquals(now, ImportMerge.leftAtOnImport(raw(ItemState.DONE, 10_000L, null), now))
        assertEquals(now, ImportMerge.leftAtOnImport(raw(ItemState.FREED, 10_000L, null), now))
        // Never released: never left.
        assertNull(ImportMerge.leftAtOnImport(raw(ItemState.SKIP, null, null), now))
    }
}
