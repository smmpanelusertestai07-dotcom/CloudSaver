package com.pocketide.sync

import com.pocketide.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DataBudgetTest {
    private val clock = FakeClock(Instant.parse("2026-09-24T10:00:00Z").toEpochMilli())
    private val network = FakeNetwork(metered = true)
    private val counters = MemoryCounters()
    private var settings = Settings()
    private val budget = MeteredDataBudget({ settings }, network, counters, clock)
    private val mb = MeteredDataBudget.MB

    @Test
    fun wifiIsFreeAndNeverCounted() {
        network.metered = false
        settings = settings.copy(mobileDailyLimitMb = 0)
        assertTrue(budget.allow(5_000 * mb, "restore", big = true).allowed)
        budget.record(10 * mb, "sync")
        assertEquals(0L, budget.usage.value.monthMeteredBytes)
    }

    @Test
    fun bigTransfersWaitForWifiByDefault() {
        val decision = budget.allow(mb, "restore", big = true)
        assertFalse(decision.allowed)
        assertEquals("Waits for Wi-Fi", decision.reason)
        settings = settings.copy(wifiOnlyBigDownloads = false)
        assertTrue(budget.allow(mb, "restore", big = true).allowed)
    }

    @Test
    fun aDailyLimitOfZeroMeansNoMobileData() {
        settings = settings.copy(mobileDailyLimitMb = 0)
        assertFalse(budget.allow(1, "sync", big = false).allowed)
    }

    @Test
    fun theDailyLimitCountsTodaysMeteredBytes() {
        settings = settings.copy(mobileDailyLimitMb = 200)
        budget.record(150 * mb, "sync")
        assertTrue(budget.allow(50 * mb, "sync", big = false).allowed)
        val over = budget.allow(50 * mb + 1, "sync", big = false)
        assertFalse(over.allowed)
        assertEquals("Today's mobile data limit is reached", over.reason)
    }

    @Test
    fun aNewUtcDayStartsAFreshAllowanceButTheMonthKeepsCounting() {
        budget.record(199 * mb, "sync")
        budget.record(mb, "restore")
        assertFalse(budget.allow(mb, "sync", big = false).allowed)
        clock.now = Instant.parse("2026-09-25T00:00:01Z").toEpochMilli()
        assertTrue(budget.allow(mb, "sync", big = false).allowed)
        budget.record(3 * mb, "sync")
        val usage = budget.usage.value
        assertEquals(3 * mb, usage.todayMeteredBytes)
        assertEquals(203 * mb, usage.monthMeteredBytes)
        assertEquals(mapOf("sync" to 202 * mb, "restore" to mb), usage.byType)
    }

    @Test
    fun dataSaverHoldsBackBigTransfersOnly() {
        settings = settings.copy(wifiOnlyBigDownloads = false)
        network.dataSaver = true
        assertFalse(budget.allow(mb, "restore", big = true).allowed)
        assertTrue(budget.allow(mb, "sync", big = false).allowed)
    }

    @Test
    fun countersOlderThanLastMonthAreDropped() {
        budget.record(mb, "sync")
        clock.now = Instant.parse("2026-10-02T10:00:00Z").toEpochMilli()
        budget.record(mb, "sync")
        clock.now = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli()
        budget.record(mb, "sync")
        assertTrue(counters.values.keys.none { it.contains("2026-09") })
        assertTrue(counters.values.keys.any { it.contains("2026-10") })
        assertEquals(mb, budget.usage.value.monthMeteredBytes)
    }

    @Test
    fun refreshShowsANewDayWithoutATransfer() {
        budget.record(5 * mb, "sync")
        clock.now = Instant.parse("2026-09-25T08:00:00Z").toEpochMilli()
        budget.refresh()
        assertEquals(0L, budget.usage.value.todayMeteredBytes)
        assertEquals(5 * mb, budget.usage.value.monthMeteredBytes)
    }
}
