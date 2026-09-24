package com.pocketide.ui.manage

import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ActionsUsageTest {
    private val september = ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun line(sku: String, quantity: Double, unit: String = "Minutes", product: String = "actions", usd: Double = 0.0) =
        UsageLine(product, sku, quantity, unit, usd)

    @Test
    fun `minutes are grouped by system with GitHub's multipliers`() {
        val usage = AccountUsage(
            plan = "free",
            lines = listOf(
                line("actions_linux", 100.0),
                line("actions_linux_arm", 20.0),
                line("actions_windows", 10.0),
                line("actions_macos", 5.0),
                line("copilot_premium_request", 50.0, unit = "Requests", product = "copilot"),
            ),
            periodStart = null,
            periodEnd = null,
        )
        val summary = ActionsUsage.summarize(usage)
        assertEquals(listOf(RunnerOs.LINUX to 120.0, RunnerOs.WINDOWS to 10.0, RunnerOs.MACOS to 5.0), summary.byOs.map { it.os to it.minutes })
        assertEquals(120.0 + 20.0 + 50.0, summary.countedMinutes, 0.001)
        assertEquals(2_000, summary.allowance?.minutes)
    }

    @Test
    fun `larger runner skus still count by system`() {
        assertEquals(RunnerOs.LINUX, ActionsUsage.osOf("linux_4_core_arm"))
        assertEquals(RunnerOs.WINDOWS, ActionsUsage.osOf("windows_8_core"))
        assertEquals(RunnerOs.MACOS, ActionsUsage.osOf("macos_xl"))
        assertNull(ActionsUsage.osOf("actions_storage"))
    }

    @Test
    fun `storage is read in GB-hours and compared with the month's allowance`() {
        val usage = AccountUsage(
            "pro",
            listOf(line("actions_storage", 360.0, unit = "GigabyteHours"), line("actions_linux", 1.0, usd = 0.5)),
            null,
            null,
        )
        val summary = ActionsUsage.summarize(usage)
        assertEquals(360.0, summary.storageGbHours, 0.001)
        assertEquals(0.5, summary.chargedUsd, 0.001)
        // Pro includes 1 GB for the month: 30 days × 24 h = 720 GB-hours.
        assertEquals(0.5, ActionsUsage.storageShare(360.0, summary.allowance, september)!!, 0.0001)
        assertNull(ActionsUsage.storageShare(360.0, null, september))
    }

    @Test
    fun `plans map to their included minutes and storage`() {
        assertEquals(2_000, ActionsUsage.allowance("Free")?.minutes)
        assertEquals(3_000, ActionsUsage.allowance("pro")?.minutes)
        assertEquals(2_000_000_000L, ActionsUsage.allowance("team")?.artifactStorageBytes)
        assertEquals(50_000, ActionsUsage.allowance("enterprise")?.minutes)
        assertNull(ActionsUsage.allowance("something new"))
        assertNull(ActionsUsage.allowance(null))
    }

    @Test
    fun `usage resets on the first of next month`() {
        val reset = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(reset, ActionsUsage.resetAt(september))
        val december = ZonedDateTime.of(2026, 12, 31, 23, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), ActionsUsage.resetAt(december))
        assertEquals(720, ActionsUsage.hoursInMonth(september))
    }

    @Test
    fun `repository size is in kilobytes`() {
        assertEquals(2048L, RepoSize.bytes(2))
        assertEquals(0L, RepoSize.bytes(-1))
    }
}
