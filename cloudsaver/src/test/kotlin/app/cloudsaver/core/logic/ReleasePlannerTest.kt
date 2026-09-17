package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePlannerTest {

    private fun staged(id: Long, mb: Long, captureAt: Long) =
        ReleasePlanner.Staged(id, mb * 1_000_000, captureAt)

    @Test
    fun newestFirstWithinBudget() {
        val plan = ReleasePlanner.plan(
            listOf(
                staged(1, 100, captureAt = 100),
                staged(2, 100, captureAt = 300),
                staged(3, 100, captureAt = 200)
            ),
            capBytes = 250_000_000
        )
        assertEquals(listOf(2L, 3L), plan)
    }

    @Test
    fun alwaysAtLeastOneEvenIfOverCap() {
        val plan = ReleasePlanner.plan(
            listOf(staged(1, 900, captureAt = 100)),
            capBytes = 250_000_000
        )
        assertEquals(listOf(1L), plan)
    }

    @Test
    fun fillsBudgetWithSmallerOlderFilesWhenNewestIsHuge() {
        val plan = ReleasePlanner.plan(
            listOf(
                staged(1, 40, captureAt = 400),
                staged(2, 300, captureAt = 300), // does not fit after #1
                staged(3, 50, captureAt = 200)
            ),
            capBytes = 100_000_000
        )
        assertEquals(listOf(1L, 3L), plan)
    }

    @Test
    fun unlimitedReleasesEverything() {
        val plan = ReleasePlanner.plan(
            (1L..5L).map { staged(it, 500, captureAt = it) },
            capBytes = -1
        )
        assertEquals(5, plan.size)
    }

    @Test
    fun emptyInputEmptyPlan() {
        assertTrue(ReleasePlanner.plan(emptyList(), 250_000_000).isEmpty())
    }

    @Test
    fun stopsOnceCapIsReached() {
        val plan = ReleasePlanner.plan(
            listOf(
                staged(1, 250, captureAt = 300),
                staged(2, 10, captureAt = 200)
            ),
            capBytes = 250_000_000
        )
        assertEquals(listOf(1L), plan)
    }
}
