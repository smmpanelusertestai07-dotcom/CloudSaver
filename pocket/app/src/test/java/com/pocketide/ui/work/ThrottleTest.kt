package com.pocketide.ui.work

import com.pocketide.ui.web.Throttle
import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottleTest {
    @Test
    fun `passes the first call, then at most once per interval`() {
        var now = 1_000L
        val throttle = Throttle(60_000) { now }
        val passed = mutableListOf<Boolean>()
        passed += throttle.ready()
        now += 30_000
        passed += throttle.ready()
        now += 29_999
        passed += throttle.ready()
        now += 1
        passed += throttle.ready()
        passed += throttle.ready()
        assertEquals(listOf(true, false, false, true, false), passed)
    }
}
