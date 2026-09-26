package com.pocketide.usage

import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** This month's use of Codespaces and Actions, read from GitHub's billing report. */
data class CloudUsage(
    val plan: String?,
    val coreHoursUsed: Double,
    val storageGbMonths: Double,
    val actionsMinutes: Double,
    /** Core-hours used on each day of the month so far; index 0 is the 1st. */
    val coreHoursByDay: List<Double>,
    /** What GitHub will charge for this month so far, after the free allowance (US dollars). */
    val billedUsd: Double,
    val month: YearMonth,
    /** Set when GitHub does not share usage with apps for this account. */
    val unavailableReason: String?,
    val readAtMs: Long,
) {
    val allowance: Allowance? get() = Allowance.forPlan(plan)

    /** Core-hours left in the free allowance; null when the plan's allowance is not known. */
    val coreHoursLeft: Double? get() = allowance?.let { (it.coreHours - coreHoursUsed).coerceAtLeast(0.0) }

    /** Hours left on the smallest (2-core) machine, the way GitHub's "60 hours" is counted. */
    val hoursLeftOnTwoCores: Double? get() = coreHoursLeft?.div(TWO_CORES)

    /** Core-hours the month will end with at this month's pace so far. */
    fun projectedCoreHours(today: LocalDate): Double {
        val day = today.dayOfMonth.coerceAtMost(month.lengthOfMonth())
        return if (day == 0) coreHoursUsed else coreHoursUsed / day * month.lengthOfMonth()
    }

    companion object {
        const val TWO_CORES = 2.0

        fun from(report: AccountUsage, month: YearMonth, readAtMs: Long): CloudUsage {
            val codespaces = report.lines.filter { it.product.equals("codespaces", ignoreCase = true) }
            val (storage, compute) = codespaces.partition { it.sku.contains("storage", ignoreCase = true) }
            val byDay = MutableList(month.lengthOfMonth()) { 0.0 }
            compute.forEach { line ->
                val day = line.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (day != null && YearMonth.from(day) == month) byDay[day.dayOfMonth - 1] += coreHours(line)
            }
            return CloudUsage(
                plan = report.plan,
                coreHoursUsed = compute.sumOf(::coreHours),
                storageGbMonths = storage.sumOf { gbMonths(it, month) },
                actionsMinutes = report.lines.filter { it.product.equals("actions", ignoreCase = true) }
                    .filter { it.unit.contains("minute", ignoreCase = true) }
                    .sumOf { it.quantity },
                coreHoursByDay = byDay,
                billedUsd = report.lines.sumOf { it.netAmountUsd },
                month = month,
                unavailableReason = report.unavailableReason,
                readAtMs = readAtMs,
            )
        }

        /**
         * Compute time as core-hours. GitHub reports hours per machine size ("Codespaces compute
         * 2-core"), so hours times cores; a line already counted in core-hours is taken as it is.
         */
        internal fun coreHours(line: UsageLine): Double {
            if (line.unit.contains("core", ignoreCase = true)) return line.quantity
            val cores = CORES.find(line.sku)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return line.quantity * cores
        }

        /** Storage as GB-months, from GB-hours when GitHub reports it that way. */
        internal fun gbMonths(line: UsageLine, month: YearMonth): Double {
            val unit = line.unit.lowercase(Locale.ROOT)
            return if ("hour" in unit) line.quantity / (month.lengthOfMonth() * HOURS_PER_DAY) else line.quantity
        }

        private val CORES = Regex("(\\d+)\\s*[-_ ]?core", RegexOption.IGNORE_CASE)
        private const val HOURS_PER_DAY = 24.0
    }
}

/**
 * The free allowance each month for personal accounts, as GitHub publishes it. It can change, so
 * the screens say when it was checked and link to GitHub's own page.
 */
data class Allowance(val coreHours: Int, val storageGbMonths: Int, val actionsMinutes: Int) {
    companion object {
        const val CHECKED_ON = "26 Sep 2026"
        const val SOURCE = "https://docs.github.com/en/billing/concepts/product-billing/github-codespaces"

        private val FREE = Allowance(coreHours = 120, storageGbMonths = 15, actionsMinutes = 2_000)
        private val PRO = Allowance(coreHours = 180, storageGbMonths = 20, actionsMinutes = 3_000)

        /** Null for plans whose allowance is not a personal one (organisations set their own). */
        fun forPlan(plan: String?): Allowance? = when (plan?.lowercase(Locale.ROOT)) {
            "free" -> FREE
            "pro" -> PRO
            else -> null
        }
    }
}

/** Live usage for the Usage screen: never frozen numbers. */
interface UsageReporter {
    suspend fun read(): CloudUsage
}
