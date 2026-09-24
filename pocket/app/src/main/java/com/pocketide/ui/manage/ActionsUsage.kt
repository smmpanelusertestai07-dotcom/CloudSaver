package com.pocketide.ui.manage

import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/** Runner systems as GitHub counts them against the included minutes (Linux 1×, Windows 2×, macOS 10×). */
enum class RunnerOs(val label: String, val multiplier: Int) {
    LINUX("Linux", 1),
    WINDOWS("Windows", 2),
    MACOS("macOS", 10),
}

data class OsMinutes(val os: RunnerOs, val minutes: Double) {
    /** Minutes as they count against the plan's included minutes. */
    val counted: Double get() = minutes * os.multiplier
}

/** What a plan includes each month. GitHub's plan table, as of September 2026. */
data class PlanAllowance(val minutes: Int, val artifactStorageBytes: Long)

data class ActionsSummary(
    val plan: String?,
    val byOs: List<OsMinutes>,
    val allowance: PlanAllowance?,
    /** Actions storage this month in GB-hours (GitHub bills storage by the hour). */
    val storageGbHours: Double,
    val chargedUsd: Double,
) {
    val countedMinutes: Double get() = byOs.sumOf { it.counted }
}

/**
 * Reads GitHub's billing-usage lines (product `actions`, SKUs such as `actions_linux`,
 * `actions_windows_arm`, `actions_macos`, `actions_storage`) into the Usage screen's numbers.
 */
object ActionsUsage {
    const val ALLOWANCE_AS_OF = "September 2026"
    private const val GB = 1_000_000_000L

    fun summarize(usage: AccountUsage): ActionsSummary {
        val actions = usage.lines.filter { isActions(it) }
        val minutes = actions.filter { it.unit.contains("minute", ignoreCase = true) }
        val byOs = RunnerOs.entries.mapNotNull { os ->
            val total = minutes.filter { osOf(it.sku) == os }.sumOf { it.quantity }
            if (total > 0) OsMinutes(os, total) else null
        }
        val storage = actions.filter { it.sku.contains("storage", ignoreCase = true) && isGbHours(it.unit) }
        return ActionsSummary(
            plan = usage.plan?.takeIf { it.isNotBlank() },
            byOs = byOs,
            allowance = allowance(usage.plan),
            storageGbHours = storage.sumOf { it.quantity },
            chargedUsd = usage.lines.sumOf { it.netAmountUsd }.coerceAtLeast(0.0),
        )
    }

    fun osOf(sku: String): RunnerOs? {
        val s = sku.lowercase(Locale.ROOT)
        return when {
            s.contains("macos") -> RunnerOs.MACOS
            s.contains("windows") -> RunnerOs.WINDOWS
            s.contains("linux") -> RunnerOs.LINUX
            else -> null
        }
    }

    fun allowance(plan: String?): PlanAllowance? {
        val p = plan?.lowercase(Locale.ROOT) ?: return null
        return when {
            p.contains("enterprise") -> PlanAllowance(50_000, 50 * GB)
            p.contains("team") -> PlanAllowance(3_000, 2 * GB)
            p.contains("pro") -> PlanAllowance(3_000, 1 * GB)
            p.contains("free") -> PlanAllowance(2_000, GB / 2)
            else -> null
        }
    }

    /**
     * The share of the included artifact storage used this month. GitHub adds up GB-hours, so
     * the included amount for a month is its size times the hours in that month.
     */
    fun storageShare(gbHours: Double, allowance: PlanAllowance?, nowMs: Long): Double? {
        if (allowance == null || allowance.artifactStorageBytes <= 0) return null
        val includedGbHours = allowance.artifactStorageBytes.toDouble() / GB * hoursInMonth(nowMs)
        return gbHours / includedGbHours
    }

    fun hoursInMonth(nowMs: Long): Int = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC)).lengthOfMonth() * 24

    /** Included minutes reset at the start of each month (UTC). */
    fun resetAt(nowMs: Long): Long =
        YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC)).plusMonths(1).atDay(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun isActions(line: UsageLine): Boolean =
        line.product.equals("actions", ignoreCase = true) || line.sku.startsWith("actions_", ignoreCase = true)

    private fun isGbHours(unit: String): Boolean {
        val u = unit.lowercase(Locale.ROOT)
        return u.contains("hour") && (u.contains("gb") || u.contains("gigabyte"))
    }
}

/** A repository against GitHub's recommended maximum on-disk size (10 GB). */
object RepoSize {
    const val GUIDELINE_BYTES = 10 * 1_000_000_000L

    /** GitHub reports repository size in kilobytes. */
    fun bytes(sizeKb: Long): Long = sizeKb.coerceAtLeast(0) * 1024
}
