package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerExpiryTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 1_790_000_000_000L

    @Test
    fun `off, never used, or far away shows nothing`() {
        assertNull(ComputerExpiry.daysLeft(now - day, -1, now))
        assertNull(ComputerExpiry.daysLeft(now - day, 0, now))
        assertNull(ComputerExpiry.daysLeft(null, 90, now))
        assertNull(ComputerExpiry.daysLeft(now - 10 * day, 90, now))
    }

    @Test
    fun `the chip appears within two weeks and counts down to zero`() {
        assertEquals(14, ComputerExpiry.daysLeft(now - 76 * day, 90, now))
        assertEquals(1, ComputerExpiry.daysLeft(now - 89 * day, 90, now))
        assertEquals(0, ComputerExpiry.daysLeft(now - 90 * day, 90, now))
        assertEquals(0, ComputerExpiry.daysLeft(now - 400 * day, 90, now))
    }

    @Test
    fun `a clock set backwards never shows more days than the rule`() {
        assertNull(ComputerExpiry.daysLeft(now + 30 * day, 90, now))
        assertEquals(10, ComputerExpiry.daysLeft(now + 30 * day, 10, now))
    }

    @Test
    fun `last work is the newest real time from sessions and projects`() {
        assertEquals(30L, ComputerExpiry.lastWork(listOf(10, 30), listOf(20)))
        assertEquals(40L, ComputerExpiry.lastWork(listOf(0), listOf(40)))
        assertNull(ComputerExpiry.lastWork(emptyList(), listOf(0)))
    }

    @Test
    fun `chip words`() {
        assertEquals("Unused: removal notice due", ComputerExpiry.chip(0))
        assertEquals("1 day left before it counts as unused", ComputerExpiry.chip(1))
        assertEquals("9 days left before it counts as unused", ComputerExpiry.chip(9))
    }
}
