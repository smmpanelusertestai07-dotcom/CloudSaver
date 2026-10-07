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
    fun `a staged row is taken over too, and a released copy waiting for Ente comes back for the watch`() {
        assertTrue(ImportMerge.plan(scanned(ItemState.STAGED, "bbbb"), item(ItemState.DONE, Evidence.VERIFIED)).takeOver)
        val waiting = item(ItemState.RELEASED)
        assertEquals(ItemState.UNKNOWN, waiting.state)
        assertTrue(ImportMerge.plan(scanned(), waiting).takeOver)
        assertEquals(ItemState.UNKNOWN, ImportMerge.takenOverState(waiting))
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
}
