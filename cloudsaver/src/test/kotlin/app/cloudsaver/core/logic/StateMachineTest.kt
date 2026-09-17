package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineTest {

    @Test
    fun evidencePrecedenceStrongestWins() {
        assertEquals(Evidence.CONFIRMED_EXACT, StateMachine.strongest(Evidence.CONFIRMED_EXACT, Evidence.VERIFIED))
        assertEquals(Evidence.CONFIRMED_EXACT, StateMachine.strongest(Evidence.AGED, Evidence.CONFIRMED_EXACT))
        assertEquals(Evidence.VERIFIED, StateMachine.strongest(Evidence.VERIFIED, Evidence.AGED))
        assertEquals(Evidence.AGED, StateMachine.strongest(Evidence.NONE, Evidence.AGED))
        assertEquals(Evidence.NONE, StateMachine.strongest(Evidence.NONE, Evidence.NONE))
        // Ordinal order IS the strength order - guard against reordering the enum.
        assertTrue(Evidence.CONFIRMED_EXACT.ordinal > Evidence.VERIFIED.ordinal)
        assertTrue(Evidence.VERIFIED.ordinal > Evidence.AGED.ordinal)
        assertTrue(Evidence.AGED.ordinal > Evidence.NONE.ordinal)
    }

    @Test
    fun happyPathTransitionsAreAllowed() {
        assertTrue(StateMachine.isAllowed(ItemState.NEW, ItemState.STAGED))
        assertTrue(StateMachine.isAllowed(ItemState.STAGED, ItemState.RELEASED))
        assertTrue(StateMachine.isAllowed(ItemState.RELEASED, ItemState.GONE))
        assertTrue(StateMachine.isAllowed(ItemState.GONE, ItemState.DONE))
        assertTrue(StateMachine.isAllowed(ItemState.DONE, ItemState.FREED))
    }

    @Test
    fun selfHealTransitionsAreAllowed() {
        assertTrue(StateMachine.isAllowed(ItemState.STAGED, ItemState.NEW)) // stage file lost
        assertTrue(StateMachine.isAllowed(ItemState.RELEASED, ItemState.NEW)) // copy user-deleted, no proof
        assertTrue(StateMachine.isAllowed(ItemState.UNKNOWN, ItemState.NEW)) // explicit reprocess
        assertTrue(StateMachine.isAllowed(ItemState.NEW, ItemState.DONE)) // original vanished
    }

    @Test
    fun forbiddenTransitions() {
        assertFalse(StateMachine.isAllowed(ItemState.NEW, ItemState.RELEASED)) // must stage first
        assertFalse(StateMachine.isAllowed(ItemState.FREED, ItemState.NEW)) // freed is final
        assertFalse(StateMachine.isAllowed(ItemState.NEW, ItemState.FREED)) // free-up needs proof
        assertFalse(StateMachine.isAllowed(ItemState.STAGED, ItemState.GONE))
    }

    @Test
    fun importMappingEvidenceLessBecomesUnknown() {
        assertEquals(
            ItemState.UNKNOWN to Evidence.NONE,
            StateMachine.importedState(ItemState.RELEASED, Evidence.NONE)
        )
        assertEquals(
            ItemState.UNKNOWN to Evidence.NONE,
            StateMachine.importedState(ItemState.DONE, Evidence.NONE)
        )
        assertEquals(
            ItemState.UNKNOWN to Evidence.NONE,
            StateMachine.importedState(ItemState.GONE, Evidence.NONE)
        )
    }

    @Test
    fun importMappingKeepsEvidenceAndTerminalStates() {
        assertEquals(
            ItemState.DONE to Evidence.CONFIRMED_EXACT,
            StateMachine.importedState(ItemState.RELEASED, Evidence.CONFIRMED_EXACT)
        )
        assertEquals(
            ItemState.DONE to Evidence.VERIFIED,
            StateMachine.importedState(ItemState.DONE, Evidence.VERIFIED)
        )
        assertEquals(
            ItemState.FREED to Evidence.CONFIRMED_EXACT,
            StateMachine.importedState(ItemState.FREED, Evidence.CONFIRMED_EXACT)
        )
        assertEquals(
            ItemState.NEW to Evidence.NONE,
            StateMachine.importedState(ItemState.STAGED, Evidence.NONE)
        )
        assertEquals(
            ItemState.SKIP to Evidence.NONE,
            StateMachine.importedState(ItemState.SKIP, Evidence.NONE)
        )
    }
}
