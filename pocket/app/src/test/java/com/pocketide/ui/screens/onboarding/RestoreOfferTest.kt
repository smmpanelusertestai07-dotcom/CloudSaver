package com.pocketide.ui.screens.onboarding

import com.pocketide.sync.RestorePlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RestoreOfferTest {
    private class MemoryFlag(var value: Boolean = false) : RestoreOffer.Flag {
        override fun read() = value
        override fun write(value: Boolean) {
            this.value = value
        }
    }

    @Test
    fun `a rebuilt key keeps the restore on offer until it starts, across restarts`() {
        val flag = MemoryFlag()
        val offer = RestoreOffer(flag)
        assertFalse(offer.pending.value)

        offer.keyReady(restored = true)
        assertTrue(offer.pending.value)
        assertTrue("Home offers it again after the app restarts", RestoreOffer(flag).pending.value)

        offer.failed()
        assertTrue(offer.pending.value)

        offer.started()
        assertFalse(offer.pending.value)
        assertFalse(RestoreOffer(flag).pending.value)
    }

    @Test
    fun `a new key or an empty Drive offers nothing`() {
        val offer = RestoreOffer(MemoryFlag(value = true))
        offer.keyReady(restored = false)
        assertFalse(offer.pending.value)

        offer.keyReady(restored = true)
        offer.nothingInDrive()
        assertFalse(offer.pending.value)
    }

    @Test
    fun `a plan Drive cannot give is a failure to try again, not nothing`() = runTest {
        val failed = loadPlan { throw IOException("No internet connection.") }
        assertEquals(PlanLoad.Failed("No internet connection."), failed)

        val plan = RestorePlan(10, 5, 5, 1, 1, onWifi = true, freeBytes = 100)
        assertEquals(PlanLoad.Loaded(plan), loadPlan { plan })
    }
}
