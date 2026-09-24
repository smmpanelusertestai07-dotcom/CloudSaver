package com.pocketide.ui.shell

import java.util.Locale

/** Plain, short numbers for people: decimal units, as Android's own storage screens use. */
object Formats {
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

    /** "200 MB", "1 GB", or "Off" for 0: the daily mobile limit is stored in MB. */
    fun megabytes(mb: Int): String = when {
        mb <= 0 -> "Off"
        mb >= 1000 && mb % 1000 == 0 -> "${mb / 1000} GB"
        else -> "$mb MB"
    }

    /** "12:05" for a countdown; never negative. */
    fun countdown(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceIn(0, Long.MAX_VALUE - 999) + 999) / 1000
        return String.format(Locale.ENGLISH, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    /** "3.2 of 3.7 GB". */
    fun part(used: Long, total: Long): String {
        val totalText = bytes(total)
        val unit = totalText.substringAfter(' ')
        val usedText = bytes(used)
        return if (usedText.substringAfter(' ') == unit) "${usedText.substringBefore(' ')} of $totalText" else "$usedText of $totalText"
    }
}
