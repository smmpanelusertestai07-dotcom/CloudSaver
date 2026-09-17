package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletePlannerTest {

    private fun copy(
        id: Long,
        mb: Long,
        evidence: Evidence,
        ageDays: Int,
        folder: OutFolder = OutFolder.SINGLE,
        captureAt: Long = id
    ) = DeletePlanner.Copy(id, mb * 1_000_000, evidence, ageDays, folder, captureAt)

    @Test
    fun ordering_confirmedThenVerifiedThenAged_oldestFirst() {
        val copies = listOf(
            copy(1, 10, Evidence.AGED, ageDays = 20, captureAt = 1),
            copy(2, 10, Evidence.CONFIRMED_EXACT, ageDays = 1, captureAt = 5),
            copy(3, 10, Evidence.VERIFIED, ageDays = 10, captureAt = 3),
            copy(4, 10, Evidence.CONFIRMED_EXACT, ageDays = 1, captureAt = 2),
            // Anchor guard: newest overall must survive.
            copy(99, 10, Evidence.CONFIRMED_EXACT, ageDays = 1, captureAt = 100)
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = 40_000_000)
        // Confirmed oldest-first (4 then 2), then verified (3), then aged (1).
        assertEquals(listOf(4L, 2L, 3L, 1L), plan.ids)
        assertTrue(plan.agedUsed)
    }

    @Test
    fun stopsWhenEnoughFreed() {
        val copies = listOf(
            copy(1, 30, Evidence.CONFIRMED_EXACT, 1, captureAt = 1),
            copy(2, 30, Evidence.CONFIRMED_EXACT, 1, captureAt = 2),
            copy(9, 30, Evidence.CONFIRMED_EXACT, 1, captureAt = 99) // anchor
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = 25_000_000)
        assertEquals(listOf(1L), plan.ids)
        assertFalse(plan.agedUsed)
        assertEquals(30_000_000, plan.freedBytes)
    }

    @Test
    fun verifiedRespectsKeepMinDays() {
        val copies = listOf(
            copy(1, 10, Evidence.VERIFIED, ageDays = 2, captureAt = 1), // too young
            copy(2, 10, Evidence.VERIFIED, ageDays = 6, captureAt = 2),
            copy(9, 10, Evidence.CONFIRMED_EXACT, 1, captureAt = 99) // anchor
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = 100_000_000, keepMinDays = 5)
        assertEquals(listOf(2L), plan.ids)
    }

    @Test
    fun agedRespectsAgedDays() {
        val copies = listOf(
            copy(1, 10, Evidence.AGED, ageDays = 5, captureAt = 1), // too young
            copy(2, 10, Evidence.AGED, ageDays = 12, captureAt = 2),
            copy(9, 10, Evidence.NONE, 0, captureAt = 99) // anchor
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = 100_000_000, agedDays = 10)
        assertEquals(listOf(2L), plan.ids)
        assertTrue(plan.agedUsed)
    }

    @Test
    fun copiesWithoutEvidenceAreNeverDeleted() {
        val copies = listOf(
            copy(1, 10, Evidence.NONE, ageDays = 50, captureAt = 1),
            copy(9, 10, Evidence.NONE, ageDays = 50, captureAt = 99)
        )
        val plan = DeletePlanner.plan(copies, bytesToFree = 100_000_000)
        assertTrue(plan.ids.isEmpty())
    }

    @Test
    fun noWorkWhenNothingToFree() {
        val plan = DeletePlanner.plan(
            listOf(copy(1, 10, Evidence.CONFIRMED_EXACT, 1)),
            bytesToFree = 0
        )
        assertTrue(plan.ids.isEmpty())
    }

    @Test
    fun safetyPauseRules() {
        // Cloud app missing -> pause.
        assertTrue(DeletePlanner.safetyPause(cloudInstalled = false, txLast3Days = null, waitingCopies = 0))
        // Waiting copies + zero traffic for 3 days -> pause.
        assertTrue(DeletePlanner.safetyPause(true, txLast3Days = 100_000, waitingCopies = 5))
        // Healthy traffic -> no pause.
        assertFalse(DeletePlanner.safetyPause(true, txLast3Days = 500L * 1024 * 1024, waitingCopies = 5))
        // No waiting copies -> no pause.
        assertFalse(DeletePlanner.safetyPause(true, txLast3Days = 0, waitingCopies = 0))
        // Cannot measure (no usage access) -> do not pause.
        assertFalse(DeletePlanner.safetyPause(true, txLast3Days = null, waitingCopies = 5))
    }
}
