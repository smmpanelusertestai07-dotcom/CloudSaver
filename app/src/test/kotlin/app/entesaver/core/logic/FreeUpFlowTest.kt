package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeUpFlowTest {

    @Test
    fun `a removal answer reaches the removal handler whichever screen opened it`() {
        // History's launcher once sent every answer to the restore handler,
        // so a Free-up batch it opened trashed originals and was never
        // written down. The kind decides now, not the screen.
        for (kind in listOf(FreeUpFlow.Dialog.RECLAIM, FreeUpFlow.Dialog.LEGACY, FreeUpFlow.Dialog.DUPLICATES)) {
            assertEquals(kind.name, FreeUpFlow.Answer.REMOVAL, FreeUpFlow.answerGoesTo(kind))
        }
        assertEquals(FreeUpFlow.Answer.RESTORE, FreeUpFlow.answerGoesTo(FreeUpFlow.Dialog.RESTORE))
        assertEquals(FreeUpFlow.Answer.NOBODY, FreeUpFlow.answerGoesTo(null))
    }

    @Test
    fun `a ticked group's box unticks the group and nothing else`() {
        val other = setOf(1L, 2L)
        val group = listOf(10L, 11L, 12L)
        val ticked = FreeUpFlow.tickGroup(other, group, tick = true)
        assertEquals(setOf(1L, 2L, 10L, 11L, 12L), ticked)
        assertEquals(other, FreeUpFlow.tickGroup(ticked, group, tick = false))
    }

    @Test
    fun `a partly ticked group is filled, not emptied`() {
        // The box shows unticked until every file is in, so its tap asks
        // for the whole group.
        assertEquals(setOf(10L, 11L), FreeUpFlow.tickGroup(setOf(10L), listOf(10L, 11L), tick = true))
    }

    @Test
    fun `copies-only never promises a trash or speaks of originals`() {
        for (canUndo in listOf(true, false)) {
            assertEquals(
                FreeUpFlow.Wording.REMOVE_COPIES,
                FreeUpFlow.buttonWording(ReclaimRules.Mode.COPIES_ONLY, canUndo)
            )
        }
        for (permanent in listOf(true, false)) {
            assertEquals(
                FreeUpFlow.Wording.REMOVE_COPIES,
                FreeUpFlow.sheetWording(ReclaimRules.Mode.COPIES_ONLY, permanent)
            )
        }
    }

    @Test
    fun `removing originals says trash only where there is one`() {
        val mode = ReclaimRules.Mode.REPLACE_WITH_LIGHT
        assertEquals(FreeUpFlow.Wording.TRASH, FreeUpFlow.buttonWording(mode, canUndo = true))
        assertEquals(FreeUpFlow.Wording.DELETE, FreeUpFlow.buttonWording(mode, canUndo = false))
        assertEquals(FreeUpFlow.Wording.TRASH, FreeUpFlow.sheetWording(mode, permanent = false))
        assertEquals(FreeUpFlow.Wording.DELETE, FreeUpFlow.sheetWording(mode, permanent = true))
    }

    @Test
    fun `a batch can be restored for thirty days and not a moment after`() {
        val day = 86_400_000L
        val at = 1_000_000_000_000L
        assertFalse(FreeUpFlow.trashExpired(at, at))
        assertFalse(FreeUpFlow.trashExpired(at, at + 29 * day))
        assertTrue(FreeUpFlow.trashExpired(at, at + 30 * day))
        assertTrue(FreeUpFlow.trashExpired(at, at + 45 * day))
        assertEquals(at + 30 * day, FreeUpFlow.trashUntil(at))
    }
}
