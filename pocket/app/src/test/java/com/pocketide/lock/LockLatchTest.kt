package com.pocketide.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLatchTest {
    private var now = 1_000_000L
    private fun latch(enabled: Boolean = true) = LockLatch(enabled, elapsed = { now })

    @Test
    fun `locked from the start, and only a passed prompt opens it`() {
        val latch = latch()
        assertFalse(latch.unlocked.value)
        latch.onForeground()
        assertFalse(latch.unlocked.value)
        latch.passed()
        assertTrue(latch.unlocked.value)
    }

    @Test
    fun `relocks after 30 seconds in the background, not before`() {
        val latch = latch().apply { passed() }

        latch.onBackground()
        now += 29_999
        latch.onForeground()
        assertTrue(latch.unlocked.value)

        latch.onBackground()
        now += 30_000
        latch.onForeground()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `the absence counts from when the app left, not from the last return`() {
        val latch = latch().apply { passed() }
        latch.onBackground()
        now += 20_000
        // A second "background" while already away must not restart the clock.
        latch.onBackground()
        now += 15_000
        latch.onForeground()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `an errand the app started gets longer, but only if the app leaves right away`() {
        val latch = latch().apply { passed() }
        latch.onErrand()
        latch.onBackground()
        now += 2 * 60_000
        latch.onForeground()
        assertTrue(latch.unlocked.value)

        latch.onErrand()
        latch.onBackground()
        now += LockLatch.ERRAND_MS
        latch.onForeground()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `a stale errand does not weaken the next absence`() {
        val latch = latch().apply { passed() }
        latch.onErrand() // e.g. a prompt that was cancelled without leaving the app
        now += 60_000
        latch.onBackground()
        now += 45_000
        latch.onForeground()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `lock now locks at once`() {
        val latch = latch().apply { passed() }
        latch.lockNow()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `with the app lock off it is always unlocked`() {
        val latch = latch(enabled = false)
        assertTrue(latch.unlocked.value)
        latch.lockNow()
        latch.onBackground()
        now += 3_600_000
        latch.onForeground()
        assertTrue(latch.unlocked.value)
    }

    @Test
    fun `turning the lock on keeps this visit open and locks the next absence`() {
        val latch = latch(enabled = false)
        latch.setEnabled(true)
        assertTrue(latch.unlocked.value)
        latch.onBackground()
        now += 31_000
        latch.onForeground()
        assertFalse(latch.unlocked.value)
    }

    @Test
    fun `turning the lock off opens it`() {
        val latch = latch()
        latch.setEnabled(false)
        assertTrue(latch.unlocked.value)
    }
}
