package com.pocketide.usage

import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CloudUsageTest {
    private val september = YearMonth.of(2026, 9)

    private fun line(product: String, sku: String, quantity: Double, unit: String, date: String? = "2026-09-10", net: Double = 0.0) =
        UsageLine(product, sku, quantity, unit, netAmountUsd = net, date = date)

    private val report = AccountUsage(
        plan = "free",
        lines = listOf(
            line("Codespaces", "Codespaces compute 2-core", 10.0, "Hours", "2026-09-02"),
            line("codespaces", "Codespaces compute 4-core", 2.0, "Hours", "2026-09-26"),
            line("Codespaces", "Codespaces storage", 720.0, "GigabyteHours"),
            line("Actions", "Actions Linux", 300.0, "Minutes", net = 0.5),
            line("Actions", "Actions storage", 3.0, "GigabyteHours"),
        ),
        periodStart = "2026-09-01",
        periodEnd = "2026-09-30",
    )

    private val usage = CloudUsage.from(report, september, readAtMs = 0)

    @Test
    fun `compute counts in core-hours, whatever the machine`() {
        assertEquals(28.0, usage.coreHoursUsed, 1e-9)
        assertEquals(20.0, usage.coreHoursByDay[1], 1e-9)
        assertEquals(8.0, usage.coreHoursByDay[25], 1e-9)
        assertEquals(30, usage.coreHoursByDay.size)
    }

    @Test
    fun `storage in GB-hours becomes GB-months`() {
        assertEquals(1.0, usage.storageGbMonths, 1e-9)
    }

    @Test
    fun `only Actions minutes count as build minutes, and what is charged adds up`() {
        assertEquals(300.0, usage.actionsMinutes, 1e-9)
        assertEquals(0.5, usage.billedUsd, 1e-9)
    }

    @Test
    fun `hours left are counted on two cores, from the plan's allowance`() {
        assertEquals(92.0, usage.coreHoursLeft!!, 1e-9)
        assertEquals(46.0, usage.hoursLeftOnTwoCores!!, 1e-9)
        assertNull(CloudUsage.from(report.copy(plan = "enterprise"), september, 0).coreHoursLeft)
    }

    @Test
    fun `the month's pace projects to its end`() {
        assertEquals(28.0 / 10 * 30, usage.projectedCoreHours(LocalDate.of(2026, 9, 10)), 1e-9)
    }

    @Test
    fun `a core-hour line is taken as it is`() {
        assertEquals(5.0, CloudUsage.coreHours(line("Codespaces", "compute", 5.0, "CoreHours")), 1e-9)
    }
}
