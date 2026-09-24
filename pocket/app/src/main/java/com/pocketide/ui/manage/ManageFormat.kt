package com.pocketide.ui.manage

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Short, plain numbers for the manage screens: decimal units, as Android's own storage screens use. */
object ManageFormat {
    private const val KB = 1000.0
    private val units = listOf("KB", "MB", "GB", "TB")

    fun bytes(count: Long): String {
        if (count < KB) return "${count.coerceAtLeast(0)} B"
        var value = count / KB
        var unit = 0
        // 999.7 KB would print as "1000 KB"; step up to the next unit instead.
        while (value >= KB - 0.5 && unit < units.lastIndex) {
            value /= KB
            unit++
        }
        val tenths = Math.round(value * 10) / 10.0
        val text = if (value >= 100 || tenths == Math.floor(tenths)) {
            String.format(Locale.ENGLISH, "%.0f", value)
        } else {
            String.format(Locale.ENGLISH, "%.1f", tenths)
        }
        return "$text ${units[unit]}"
    }

    /** GitHub bills whole minutes, so partial minutes round up: "1,235 min". */
    fun minutes(value: Double): String {
        val whole = ceil(value.coerceAtLeast(0.0) - 1e-9).toLong()
        return String.format(Locale.ENGLISH, "%,d min", whole)
    }

    /** Whole percent of [part] in [whole], or null when there is nothing to compare against. */
    fun percent(part: Double, whole: Double): Int? {
        if (whole <= 0.0 || part.isNaN() || whole.isNaN()) return null
        return (part / whole * 100).roundToLong().coerceIn(0, 999).toInt()
    }

    /** "42%", "<1%" for a small but real share, "0%" for nothing, "" when unknown. */
    fun percentText(part: Double, whole: Double): String {
        val value = percent(part, whole) ?: return ""
        return if (value == 0 && part > 0) "<1%" else "$value%"
    }

    /** "1 session", "3 sessions". */
    fun count(n: Int, one: String, many: String = one + "s"): String =
        "${String.format(Locale.ENGLISH, "%,d", n)} ${if (n == 1) one else many}"

    fun usd(amount: Double): String = String.format(Locale.ENGLISH, "$%.2f", amount)

    /** Download counts on agent cards: "52K", "1.3M". */
    fun downloads(count: Long): String = when {
        count >= 1_000_000 -> trimmed(count / 1_000_000.0) + "M"
        count >= 1_000 -> trimmed(count / 1_000.0) + "K"
        else -> count.coerceAtLeast(0).toString()
    }

    /** "Every hour", "Every 6 hours", "Every day", "Every 3 days", "Every week". */
    fun every(hours: Int): String = when {
        hours <= 1 -> "Every hour"
        hours == 24 -> "Every day"
        hours == 168 -> "Every week"
        hours % 24 == 0 -> "Every ${hours / 24} days"
        else -> "Every $hours hours"
    }

    /** "in 5 min", "in 3 h", "in 2 days"; "now" for anything due. */
    fun inFuture(deltaMs: Long): String {
        if (deltaMs <= 60_000) return "now"
        val minutes = deltaMs / 60_000
        return when {
            minutes < 60 -> "in $minutes min"
            minutes < 48 * 60 -> "in ${(minutes + 30) / 60} h"
            else -> "in ${(minutes + 12 * 60) / (24 * 60)} days"
        }
    }

    private fun trimmed(value: Double): String {
        val tenths = Math.round(value * 10) / 10.0
        return if (value >= 100 || tenths == Math.floor(tenths)) {
            String.format(Locale.ENGLISH, "%.0f", value)
        } else {
            String.format(Locale.ENGLISH, "%.1f", tenths)
        }
    }
}
