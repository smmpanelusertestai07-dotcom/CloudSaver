package app.cloudsaver.core.logic

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
    fun `the fingerprint survives the round trip through a filename`() {
        // What the whole re-attach depends on: the copy's name still names the
        // original, even after MediaStore has added a de-dup suffix.
        val fp = Fingerprint.fp16("IMG_0042.jpg", 4_000_000L, 1_700_000_000L)
        val name = Fingerprint.outputName("IMG_0042.jpg", fp, "jpg")
        assertEquals(fp, Fingerprint.fpFromOutputName(name))
        assertEquals(fp, Fingerprint.fpFromOutputName("IMG_0042__$fp (1).jpg"))
    }
}
