package com.pocketide.sync

import com.pocketide.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AgentTrafficTest {
    private val clock = FakeClock(Instant.parse("2026-09-24T10:00:00Z").toEpochMilli())
    private val network = FakeNetwork(metered = true)
    private var settings = Settings(mobileDailyLimitMb = 200)
    private val budget = MeteredDataBudget({ settings }, network, MemoryCounters(), clock)
    private val mb = MeteredDataBudget.MB
    private var appBytes = 1_000 * mb
    private val meter = AgentTrafficMeter(budget, network) { appBytes }
    private val agents get() = budget.usage.value.byType[MeteredDataBudget.KIND_AGENT_TRAFFIC] ?: 0L

    @Test
    fun theAppsTrafficLessPocketIdesOwnTransfersIsTheAgents() {
        meter.sample()
        appBytes += 30 * mb
        budget.record(10 * mb, MeteredDataBudget.KIND_SYNC)
        meter.sample()

        assertEquals(20 * mb, agents)
        val usage = budget.usage.value
        assertEquals(30 * mb, usage.todayMeteredBytes)
        assertEquals(30 * mb, usage.monthMeteredBytes)
        assertEquals("only PocketIDE's own transfers count against the limit", 10 * mb, usage.todayLimitedBytes)
    }

    @Test
    fun theAgentsAreCountedButNeverUseUpTheDailyLimit() {
        meter.sample()
        appBytes += 500 * mb
        meter.sample()

        assertEquals(500 * mb, agents)
        assertTrue("sync still has today's share", budget.allow(150 * mb, MeteredDataBudget.KIND_SYNC, big = false).allowed)
    }

    @Test
    fun wifiAndTheTimeBetweenRunsAreNotCounted() {
        meter.sample()
        network.metered = false
        appBytes += 40 * mb
        meter.sample()
        assertEquals(0L, agents)

        network.metered = true
        meter.stop()
        appBytes += 70 * mb
        meter.sample()
        assertEquals("the first sample after a stop only sets the start", 0L, agents)
        appBytes += 5 * mb
        meter.sample()
        assertEquals(5 * mb, agents)
    }

    @Test
    fun anUnknownCounterIsNeverGuessed() {
        appBytes = -1
        meter.sample()
        meter.sample()
        appBytes = 100 * mb
        meter.sample()
        assertEquals(0L, agents)
    }
}
