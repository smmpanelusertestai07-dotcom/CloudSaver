package app.cloudsaver.core.logic

import app.cloudsaver.core.logic.RowActions.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowActionsTest {

    private fun row(
        state: ItemState,
        evidence: Evidence = Evidence.NONE,
        neverOptimise: Boolean = false,
        originalMissing: Boolean = false
    ) = RowActions.Row(state, evidence, neverOptimise, originalMissing)

    @Test
    fun `a waiting file can be pushed forward or skipped`() {
        val actions = RowActions.forItem(row(ItemState.NEW))
        assertEquals(
            listOf(Action.OPEN, Action.OPTIMISE_FIRST, Action.NEVER_OPTIMISE),
            actions
        )
    }

    @Test
    fun `an optimised copy is never offered never-optimise`() {
        // The work is done, so the option could not undo anything. Offering it
        // is what made a phone show three optimised files and two in the
        // folder with no way to tell why.
        for (state in listOf(ItemState.STAGED, ItemState.RELEASED, ItemState.DONE)) {
            val actions = RowActions.forItem(row(state))
            assertFalse(
                "$state must not offer never-optimise",
                Action.NEVER_OPTIMISE in actions
            )
            assertFalse("$state must not offer optimise-first", Action.OPTIMISE_FIRST in actions)
        }
    }

    @Test
    fun `remove from phone appears only with per-file proof`() {
        assertFalse(
            Action.REMOVE_FROM_PHONE in RowActions.forItem(
                row(ItemState.DONE, Evidence.NONE)
            )
        )
        assertFalse(
            "an aged guess is not proof",
            Action.REMOVE_FROM_PHONE in RowActions.forItem(row(ItemState.DONE, Evidence.AGED))
        )
        assertFalse(
            "a size match alone is not per-file proof",
            Action.REMOVE_FROM_PHONE in RowActions.forItem(row(ItemState.DONE, Evidence.VERIFIED))
        )
        assertTrue(
            Action.REMOVE_FROM_PHONE in RowActions.forItem(
                row(ItemState.DONE, Evidence.CONFIRMED_EXACT)
            )
        )
        assertTrue(
            Action.REMOVE_FROM_PHONE in RowActions.forItem(
                row(ItemState.DONE, Evidence.CONFIRMED_PACED)
            )
        )
    }

    @Test
    fun `a skipped file can be retried, or skipped for good`() {
        val actions = RowActions.forItem(row(ItemState.SKIP))
        assertEquals(listOf(Action.OPEN, Action.TRY_AGAIN, Action.NEVER_OPTIMISE), actions)
    }

    @Test
    fun `a file the user skipped for good offers the way back`() {
        val actions = RowActions.forItem(row(ItemState.NEW, neverOptimise = true))
        assertTrue(Action.ALLOW_AGAIN in actions)
        assertFalse(Action.NEVER_OPTIMISE in actions)
        assertFalse("a skipped file is not queued ahead", Action.OPTIMISE_FIRST in actions)
    }

    @Test
    fun `a missing original can only be looked at`() {
        assertEquals(
            listOf(Action.OPEN),
            RowActions.forItem(
                row(ItemState.DONE, Evidence.CONFIRMED_EXACT, originalMissing = true)
            )
        )
    }

    @Test
    fun `an already reclaimed file offers nothing further`() {
        assertEquals(listOf(Action.OPEN), RowActions.forItem(row(ItemState.FREED)))
        assertEquals(listOf(Action.OPEN), RowActions.forItem(row(ItemState.FREED_KEPT)))
    }

    @Test
    fun `the keeper of a duplicate group cannot be removed`() {
        assertEquals(listOf(Action.OPEN), RowActions.forDuplicate(isKeeper = true))
        assertEquals(
            listOf(Action.OPEN, Action.REMOVE_EXTRA, Action.KEEP_THIS_INSTEAD),
            RowActions.forDuplicate(isKeeper = false)
        )
    }

    @Test
    fun `a mixed selection splits into eligible and skipped, with the counts`() {
        // CC6.1: five selected, two already optimised - the bar must offer
        // "Optimise 3 of 5" and name the two it skips.
        val rows = listOf(
            1L to row(ItemState.NEW),
            2L to row(ItemState.NEW),
            3L to row(ItemState.SKIP),
            4L to row(ItemState.RELEASED),
            5L to row(ItemState.DONE)
        )
        val split = RowActions.splitForOptimise(rows)
        assertEquals(listOf(1L, 2L, 3L), split.eligibleIds)
        assertEquals(3, split.eligible)
        assertEquals(2, split.skipped)
    }

    @Test
    fun `zero eligible means the action is hidden, and the split says so`() {
        // CC6.2: hidden, not greyed - a control that can do nothing for this
        // selection is absent.
        val rows = listOf(
            1L to row(ItemState.RELEASED),
            2L to row(ItemState.DONE)
        )
        assertEquals(0, RowActions.splitForOptimise(rows).eligible)
        // And the generic splitter agrees for any action.
        assertEquals(
            0,
            RowActions.splitFor(RowActions.Action.OPTIMISE_FIRST, rows).eligible
        )
    }

    @Test
    fun `free up counts only proof-carrying items`() {
        // CC6.3: the same split rule, for removal.
        val rows = listOf(
            1L to row(ItemState.DONE, Evidence.CONFIRMED_EXACT),
            2L to row(ItemState.DONE, Evidence.NONE),
            3L to row(ItemState.DONE, Evidence.CONFIRMED_PACED)
        )
        val split = RowActions.splitFor(RowActions.Action.REMOVE_FROM_PHONE, rows)
        assertEquals(listOf(1L, 3L), split.eligibleIds)
        assertEquals(1, split.skipped)
    }

    @Test
    fun `removable count ignores what has no proof or is already gone`() {
        val rows = listOf(
            row(ItemState.DONE, Evidence.CONFIRMED_EXACT),
            row(ItemState.DONE, Evidence.CONFIRMED_PACED),
            row(ItemState.DONE, Evidence.NONE),
            row(ItemState.FREED, Evidence.CONFIRMED_EXACT),
            row(ItemState.DONE, Evidence.CONFIRMED_EXACT, originalMissing = true)
        )
        assertEquals(2, RowActions.removableCount(rows))
    }
}
