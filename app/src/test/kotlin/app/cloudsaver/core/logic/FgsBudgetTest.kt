package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsBudgetTest {

    private val hour = 3_600_000L

    @Test
    fun sumsOnlyTheLast24Hours() {
        val now = 100 * hour
        val sessions = listOf(
            (now - 30 * hour) to (now - 29 * hour), // outside window
            (now - 10 * hour) to (now - 9 * hour), // 1 h inside
            (now - 2 * hour) to (now - 1 * hour) // 1 h inside
        )
        assertEquals(2 * hour, FgsBudget.usedInWindow(sessions, now))
    }

    @Test
    fun partialOverlapCountsPartially() {
        val now = 100 * hour
        // Session started 25 h ago and ran 2 h: only 1 h falls into the window.
        val sessions = listOf((now - 25 * hour) to (now - 23 * hour))
        assertEquals(1 * hour, FgsBudget.usedInWindow(sessions, now))
    }

    @Test
    fun remainingStopsAtFiveAndAHalfHours() {
        val now = 100 * hour
        val sessions = listOf((now - 6 * hour) to (now - 0 * hour)) // 6 h used
        assertEquals(0, FgsBudget.remaining(sessions, now))
        val lighter = listOf((now - 2 * hour) to now) // 2 h used
        assertEquals(Defaults.FGS_BUDGET_MS - 2 * hour, FgsBudget.remaining(lighter, now))
    }

    @Test
    fun pruneDropsExpiredSessions() {
        val now = 100 * hour
        val sessions = listOf(
            (now - 30 * hour) to (now - 29 * hour),
            (now - 1 * hour) to now
        )
        val pruned = FgsBudget.prune(sessions, now)
        assertEquals(1, pruned.size)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val sessions = listOf(1L to 2L, 30L to 400L)
        assertEquals(sessions, FgsBudget.decode(FgsBudget.encode(sessions)))
    }

    @Test
    fun decodeIsGarbageTolerant() {
        assertTrue(FgsBudget.decode("").isEmpty())
        assertTrue(FgsBudget.decode("junk;1:;:2;5:1").isEmpty())
        assertEquals(listOf(1L to 2L), FgsBudget.decode("junk;1:2"))
    }
}
