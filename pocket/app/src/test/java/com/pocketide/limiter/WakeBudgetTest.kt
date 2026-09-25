package com.pocketide.limiter

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeBudgetTest {
    private val minute = 60_000L
    private val budget = WakeBudget(maxMs = 120 * minute, quietMs = 15 * minute)

    @Test fun `nothing working holds nothing`() {
        assertEquals(0, budget.remainingMs(0))
        budget.update(0, working = false)
        assertEquals(0, budget.remainingMs(minute))
    }

    @Test fun `a stretch of work gets the whole budget once`() {
        budget.update(0, working = true)
        assertEquals(120 * minute, budget.remainingMs(0))
        // The service's collector starts again on every change; the stretch does not.
        budget.update(60 * minute, working = true)
        assertEquals(60 * minute, budget.remainingMs(60 * minute))
        assertEquals(0, budget.remainingMs(121 * minute))
    }

    @Test fun `work that pauses for a minute at a time never gets a new budget`() {
        var now = 0L
        while (now < 8 * 60 * minute) {
            budget.update(now, working = true)
            budget.update(now + minute, working = false)
            now += 2 * minute
        }
        budget.update(now, working = true)
        assertEquals("a file watcher cannot keep the phone awake all night", 0, budget.remainingMs(now))
    }

    @Test fun `a real rest ends the stretch, and the next work starts a new one`() {
        budget.update(0, working = true)
        budget.update(130 * minute, working = true)
        assertEquals(0, budget.remainingMs(130 * minute))
        budget.update(131 * minute, working = false)
        assertEquals(0, budget.remainingMs(140 * minute))
        budget.update(147 * minute, working = true)
        assertEquals(120 * minute, budget.remainingMs(147 * minute))
    }
}
