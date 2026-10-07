package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReattachRulesTest {

    @Test
    fun `a queued item adopts a copy that is already on disk`() {
        assertTrue(ReattachRules.canAdopt(ItemState.NEW.name, hasOutput = false))
        assertTrue(ReattachRules.canAdopt(ItemState.STAGED.name, hasOutput = false))
    }

    @Test
    fun `a row that already knows its copy is left alone`() {
        assertFalse(ReattachRules.canAdopt(ItemState.NEW.name, hasOutput = true))
        assertFalse(ReattachRules.canAdopt(ItemState.STAGED.name, hasOutput = true))
    }

    @Test
    fun `a row restored without proof goes back under watch when its copy is still there`() {
        // Its output fields describe the old install's copy, so they do not
        // stop it; it keeps only what its history recorded about that copy.
        assertTrue(ReattachRules.canAdopt(ItemState.UNKNOWN.name, hasOutput = true))
        assertTrue(ReattachRules.canAdopt(ItemState.UNKNOWN.name, hasOutput = false))
    }

    @Test
    fun `evidence-bearing rows are never overwritten by a filename match`() {
        // These states carry the proof Reclaim relies on. A copy sitting in a
        // folder is not permission to rewrite that.
        for (state in listOf(
            ItemState.RELEASED, ItemState.DONE, ItemState.FREED,
            ItemState.FREED_KEPT, ItemState.GONE, ItemState.SKIP
        )) {
            assertFalse(state.name, ReattachRules.canAdopt(state.name, hasOutput = false))
        }
    }

    @Test
    fun `an adopted row claims no upload evidence`() {
        // The whole safety model rests on this: the file existing proves it
        // was made, never that a cloud app collected it.
        assertEquals(Evidence.NONE, ReattachRules.evidence)
        assertFalse(ReattachRules.evidence.isPerFile)
        assertEquals(ItemState.RELEASED, ReattachRules.state)
    }

    @Test
    fun `a restored row keeps its recorded evidence only for that very copy`() {
        val unknown = ItemState.UNKNOWN.name
        assertEquals(
            Evidence.AGED,
            ReattachRules.evidenceAfterAdopt(unknown, Evidence.AGED, sameCopy = true)
        )
        assertEquals(
            Evidence.CONFIRMED_PACED,
            ReattachRules.evidenceAfterAdopt(unknown, Evidence.CONFIRMED_PACED, sameCopy = true)
        )
        // A different file under the same fingerprint: the record was not about it.
        assertEquals(
            Evidence.NONE,
            ReattachRules.evidenceAfterAdopt(unknown, Evidence.CONFIRMED_PACED, sameCopy = false)
        )
        // A queued row has no record to keep, whatever the filename says.
        for (state in listOf(ItemState.NEW, ItemState.STAGED)) {
            assertEquals(
                Evidence.NONE,
                ReattachRules.evidenceAfterAdopt(state.name, Evidence.CONFIRMED_EXACT, sameCopy = true)
            )
        }
    }

    @Test
    fun `a restored row whose copy is gone is done only with evidence`() {
        assertEquals(ItemState.UNKNOWN, ReattachRules.stateWhenCopyMissing(Evidence.NONE))
        for (evidence in Evidence.entries - Evidence.NONE) {
            assertEquals(ItemState.DONE, ReattachRules.stateWhenCopyMissing(evidence))
        }
    }

    @Test
    fun `the fingerprint survives the round trip through a filename`() {
        // What the whole re-attach depends on: the copy's name still names the
        // original, even after MediaStore has added a de-dup suffix.
        val fp = Fingerprint.fp16("IMG_0042.jpg", 4_000_000L, 1_700_000_000L)
        val name = Fingerprint.outputName("IMG_0042.jpg", fp, "jpg")
        assertEquals(fp, Fingerprint.fpFromOutputName(name))
        assertEquals(fp, Fingerprint.fpFromOutputName("IMG_0042__$fp (1).jpg"))
    }

    @Test
    fun restoredCopiesWaitToBeMatchedUntilEveryVolumeWasLookedAt() {
        val phone = "external_primary"
        val card = "1234-abcd"
        // A restore not yet matched.
        assertTrue(ReattachRules.matchPending(false, setOf(phone, card), setOf(phone, card)))
        // Matched with every volume that is in now.
        assertFalse(ReattachRules.matchPending(true, setOf(phone, card), setOf(phone, card)))
        assertFalse(ReattachRules.matchPending(true, setOf(phone, card), setOf(phone)))
        // The card was out then and is in now: its copies were missed.
        assertTrue(ReattachRules.matchPending(true, setOf(phone), setOf(phone, card)))
        // Matched before the volumes were recorded: once more.
        assertTrue(ReattachRules.matchPending(true, emptySet(), setOf(phone)))
    }
}
