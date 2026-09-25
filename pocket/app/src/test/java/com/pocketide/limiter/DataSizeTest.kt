package com.pocketide.limiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataSizeTest {
    private val minute = 60_000L
    private var quickAnswer: Long? = 5_000L
    private var quicks = 0
    private var walks = 0
    private val size = DataSize(
        quick = { quicks++; quickAnswer },
        walk = { walks++; 7_000L },
        quickEveryMs = minute,
        walkEveryMs = 10 * minute,
    )

    @Test fun `a process woken for a background job never walks the computer`() {
        quickAnswer = null
        var now = 0L
        while (now < 24 * 60 * minute) {
            size.measureIfDue(now, visible = false)
            now += 5_000L
        }
        assertEquals(0, walks)
    }

    @Test fun `Android's own accounting is used when it answers, and the computer is never walked`() {
        assertEquals(5_000L, size.measureIfDue(0, visible = true))
        assertNull("not due again within a minute", size.measureIfDue(30_000L, visible = true))
        assertEquals(5_000L, size.measureIfDue(minute, visible = true))
        assertEquals(2, quicks)
        assertEquals(0, walks)
    }

    @Test fun `in the background Android's accounting is asked rarely`() {
        size.measureIfDue(0, visible = false)
        size.measureIfDue(5 * minute, visible = false)
        assertEquals(1, quicks)
        size.measureIfDue(10 * minute, visible = false)
        assertEquals(2, quicks)
    }

    @Test fun `without Android's answer the walk runs on screen at most every ten minutes`() {
        quickAnswer = null
        assertEquals(7_000L, size.measureIfDue(0, visible = true))
        var now = minute
        while (now < 10 * minute) {
            assertNull(size.measureIfDue(now, visible = true))
            now += minute
        }
        assertEquals(1, walks)
        assertEquals(7_000L, size.measureIfDue(10 * minute, visible = true))
        assertEquals(2, walks)
    }
}
