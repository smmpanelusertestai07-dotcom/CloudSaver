package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A waiting file is made into one light copy, by one path, from the bytes
 * its row describes.
 */
class StageRulesTest {

    private val new = ItemState.NEW.name

    @Test
    fun `a file another path already staged is not encoded again`() {
        // The trial and the scheduled run both pick the newest photos.
        for (state in listOf(ItemState.STAGED, ItemState.RELEASED, ItemState.SKIP, ItemState.DONE)) {
            assertEquals(
                StageRules.Verdict.TAKEN,
                StageRules.verdict(state.name, false, "IMG_1.jpg", 4_000_000, "IMG_1.jpg", 4_000_000)
            )
        }
        assertEquals(
            "an original that has gone is not staged",
            StageRules.Verdict.TAKEN,
            StageRules.verdict(new, true, "IMG_1.jpg", 4_000_000, "IMG_1.jpg", 4_000_000)
        )
    }

    @Test
    fun `a photo edited in place while it waited is retired, not encoded under the old row`() {
        // "Save" in a gallery editor: same address, new size.
        assertEquals(
            StageRules.Verdict.CHANGED,
            StageRules.verdict(new, false, "IMG_1.jpg", 4_000_000, "IMG_1.jpg", 3_100_000)
        )
        assertEquals(
            StageRules.Verdict.STAGE,
            StageRules.verdict(new, false, "IMG_1.jpg", 4_000_000, "IMG_1.jpg", 4_000_000)
        )
        // Not being able to look is not proof of a change.
        assertEquals(
            StageRules.Verdict.STAGE,
            StageRules.verdict(new, false, "IMG_1.jpg", 4_000_000, null, null)
        )
    }

    @Test
    fun `the scan retires a waiting row whose address now holds another file`() {
        val waiting = listOf(
            StageRules.Waiting(1, "fpA", "content://media/external/images/media/10"),
            StageRules.Waiting(2, "fpB", "content://media/external/images/media/11"),
            StageRules.Waiting(3, "fpC", "content://media/external/images/media/12")
        )
        val onPhone = mapOf(
            // Edited in place: the scan made a row for the new fingerprint.
            "content://media/external/images/media/10" to "fpA2",
            // Unchanged.
            "content://media/external/images/media/11" to "fpB"
            // 12 not seen: left to the presence check.
        )
        assertEquals(listOf(1L), StageRules.replaced(waiting, onPhone))
        assertEquals(emptyList<Long>(), StageRules.replaced(waiting, emptyMap()))
    }

    @Test
    fun `an encode keeps its result only for a row still waiting for that file`() {
        // The row was read before an encode of up to twenty minutes. A
        // restore, a "Never optimise" or a missing original settled it
        // meanwhile, and writing the stale row back undid that.
        val fp = "0123456789abcdef"
        assertEquals(true, StageRules.stillWaiting(new, false, false, fp, fp))
        for (state in listOf(ItemState.DONE, ItemState.UNKNOWN, ItemState.SKIP, ItemState.STAGED, ItemState.FREED_KEPT)) {
            assertEquals(state.name, false, StageRules.stillWaiting(state.name, false, false, fp, fp))
        }
        assertEquals("excluded meanwhile", false, StageRules.stillWaiting(new, true, false, fp, fp))
        assertEquals("original gone meanwhile", false, StageRules.stillWaiting(new, false, true, fp, fp))
        assertEquals("another file now", false, StageRules.stillWaiting(new, false, false, "fedcba9876543210", fp))
    }
}
