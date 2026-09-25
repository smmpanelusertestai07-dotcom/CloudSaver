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
    /** What the owner pays: the sum of every line's net amount. */
    val chargedUsd: Double,
    /** What the usage is worth before discounts. */
    val grossUsd: Double = 0.0,
    /** What the plan and public repositories covered. */
    val discountUsd: Double = 0.0,
    /** The cost per repository (null: the account as a whole), biggest first. */
    val byRepository: List<RepoCost> = emptyList(),
) {
    val countedMinutes: Double get() = byOs.sumOf { it.counted }
}

/** Usage of one repository this month, and how it is paid. */
data class RepoCost(val repository: String?, val grossUsd: Double, val discountUsd: Double, val netUsd: Double, val publicRepo: Boolean) {
    val label: String
        get() = when {
            publicRepo -> "free (public repo)"
            netUsd >= CENT -> ManageFormat.usd(netUsd)
            // The discount equals the gross amount: the plan's included usage paid for all of it.
            else -> "included in your plan"
        }

    private companion object {
        const val CENT = 0.005
    }
}

/**
 * Reads GitHub's billing-usage lines (product `actions`, SKUs such as `actions_linux`,
 * `actions_windows_arm`, `actions_macos`, `actions_storage`) into the Usage screen's numbers.
 */
object ActionsUsage {
    const val ALLOWANCE_AS_OF = "September 2026"
    private const val GB = 1_000_000_000L

    /**
     * [publicRepos] are the owner's public repositories, as `owner/name` or `name`: GitHub
     * discounts their standard-runner minutes in full.
     */
    fun summarize(usage: AccountUsage, publicRepos: Set<String> = emptySet()): ActionsSummary {
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
            grossUsd = usage.lines.sumOf { it.grossAmountUsd },
            discountUsd = usage.lines.sumOf { it.discountAmountUsd },
            byRepository = byRepository(actions, publicRepos),
        )
    }

    private fun byRepository(lines: List<UsageLine>, publicRepos: Set<String>): List<RepoCost> {
        val public = publicRepos.map { it.lowercase(Locale.ROOT) }.toSet()
        return lines.groupBy { it.repository?.takeIf(String::isNotBlank) }.map { (repository, group) ->
            val name = repository?.lowercase(Locale.ROOT)
            RepoCost(
                repository = repository,
                grossUsd = group.sumOf { it.grossAmountUsd },
                discountUsd = group.sumOf { it.discountAmountUsd },
                netUsd = group.sumOf { it.netAmountUsd },
                publicRepo = name != null && (name in public || name.substringAfter('/') in public),
            )
        }.sortedByDescending { it.grossUsd }
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
