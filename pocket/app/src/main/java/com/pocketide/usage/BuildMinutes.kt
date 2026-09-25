package com.pocketide.usage

import com.pocketide.builds.TemplateCatalog
import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import com.pocketide.github.WorkflowRun
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * GitHub Actions minutes included each month in private repositories, by plan. Public
 * repositories use standard runners for free. These change: they carry the date they were
 * checked and the page they come from.
 */
object ActionsPlans {
    const val AS_OF = "24 Sep 2026"
    const val BILLING_URL = "https://docs.github.com/en/billing/concepts/product-billing/github-actions"

    const val FREE = 2_000
    const val PRO = 3_000
    const val TEAM = 3_000
    const val ENTERPRISE = 50_000

    /** Included minutes for the plan GitHub names ("free", "pro", "team", "enterprise"). */
    fun includedMinutes(plan: String?): Int? {
        val p = plan?.lowercase(Locale.ROOT) ?: return null
        return when {
            "enterprise" in p -> ENTERPRISE
            "team" in p -> TEAM
            "pro" in p -> PRO
            "free" in p -> FREE
            else -> null
        }
    }

    /** How many included minutes one runner minute uses: Linux 1, Windows 2, macOS 10. */
    fun multiplier(sku: String): Int? {
        val s = sku.lowercase(Locale.ROOT)
        return when {
            "macos" in s -> 10
            "windows" in s -> 2
            "linux" in s -> 1
            else -> null
        }
    }
}

/** One finished build of the owner's, in runner minutes. */
data class BuildSample(val family: Family, val minutes: Double) {
    enum class Family { ANDROID, IOS }
}

/**
 * "About N builds left", from the owner's own finished builds and this month's usage. Nothing is
 * guessed: without a known plan, usage, or at least [MIN_SAMPLES] builds of a kind, that kind has
 * no number, and with neither kind the whole estimate is null.
 */
object BuildMinutes {
    const val MIN_SAMPLES = 2
    const val IOS_MULTIPLIER = 10

    private val ANDROID_TEMPLATES = setOf(TemplateCatalog.ANDROID_RELEASE, TemplateCatalog.FLUTTER_ANDROID, TemplateCatalog.REACT_NATIVE_ANDROID)

    /** Successful runs of PocketIDE's Android and iOS templates, with their length. */
    fun samples(runs: List<WorkflowRun>): List<BuildSample> = runs.mapNotNull { run ->
        if (run.status != "completed" || run.conclusion != "success") return@mapNotNull null
        val template = TemplateCatalog.forRunName(run.name) ?: return@mapNotNull null
        val family = when (template.id) {
            in ANDROID_TEMPLATES -> BuildSample.Family.ANDROID
            TemplateCatalog.IOS_SIMULATOR -> BuildSample.Family.IOS
            else -> return@mapNotNull null
        }
        val minutes = minutesBetween(run.createdAt, run.updatedAt) ?: return@mapNotNull null
        BuildSample(family, minutes)
    }

    /** Included minutes used this month, counted the way the plan counts them. */
    fun countedMinutesUsed(lines: List<UsageLine>): Double = lines
        .filter { it.product.equals("actions", ignoreCase = true) && it.unit.contains("minute", ignoreCase = true) }
        .sumOf { line -> (ActionsPlans.multiplier(line.sku) ?: 1) * line.quantity }

    fun estimate(usage: AccountUsage, samples: List<BuildSample>): BuildEstimate? {
        val included = ActionsPlans.includedMinutes(usage.plan) ?: return null
        val left = (included - countedMinutesUsed(usage.lines)).coerceAtLeast(0.0)
        val android = samples.filter { it.family == BuildSample.Family.ANDROID }
        val ios = samples.filter { it.family == BuildSample.Family.IOS }
        // GitHub bills each job in whole minutes, so an average build is rounded up.
        val androidEach = android.averageOrNull()?.let { ceil(it).toInt().coerceAtLeast(1) }
        val iosEach = ios.averageOrNull()?.let { ceil(it).toInt().coerceAtLeast(1) * IOS_MULTIPLIER }
        if (androidEach == null && iosEach == null) return null
        val leftMinutes = left.roundToInt()
        return BuildEstimate(
            androidLeft = androidEach?.let { (left / it).toInt() },
            iosLeft = iosEach?.let { (left / it).toInt() },
            basis = basis(android.size, androidEach, ios.size, iosEach, leftMinutes, included),
            minutesLeft = leftMinutes,
            androidMinutesEach = androidEach,
            iosMinutesEach = iosEach,
        )
    }

    private fun basis(androidRuns: Int, androidEach: Int?, iosRuns: Int, iosEach: Int?, left: Int, included: Int): String {
        val parts = listOfNotNull(
            androidEach?.let { "your last $androidRuns Android builds took about $it min each" },
            iosEach?.let { "your last $iosRuns iOS builds about ${it / IOS_MULTIPLIER} min each, which counts as $it (macOS counts 10×)" },
        )
        return "Estimate: ${parts.joinToString("; ")}. $left of your plan's $included minutes are left this month. " +
            "Run time includes waiting for a machine. Public repositories are free. Plan minutes as of ${ActionsPlans.AS_OF}: ${ActionsPlans.BILLING_URL}"
    }

    private fun List<BuildSample>.averageOrNull(): Double? =
        if (size < MIN_SAMPLES) null else map { it.minutes }.average()

    private fun minutesBetween(start: String, end: String): Double? = try {
        val ms = Instant.parse(end).toEpochMilli() - Instant.parse(start).toEpochMilli()
        if (ms <= 0) null else ms / 60_000.0
    } catch (e: DateTimeParseException) {
        null
    }
}
