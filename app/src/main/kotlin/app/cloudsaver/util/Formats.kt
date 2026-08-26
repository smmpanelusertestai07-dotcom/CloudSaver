package app.cloudsaver.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The one place numbers become text.
 *
 * Sizes use decimal units, because that is what cloud plans are sold in: a
 * "50 GB" plan means 50 000 MB, and showing binary GB here would make the
 * calculator quietly disagree with the user's bill.
 */
object Formats {

    const val KB = 1_000L
    const val MB = 1_000_000L
    const val GB = 1_000_000_000L

    /** Sizes: KB, MB (one decimal under ten), GB with two decimals. */
    fun bytes(v: Long): String {
        if (v <= 0) return "0 MB"
        return when {
            // An exact gigabyte prints as "5 GB", not "5.00 GB": the two zeros
            // are noise, and a limit the user picked as "5 GB" should read
            // back in the words they picked.
            v >= GB && v % GB == 0L -> "${v / GB} GB"
            v >= GB -> String.format(Locale.US, "%.2f GB", v.toDouble() / GB)
            v >= 10 * MB -> String.format(Locale.US, "%.0f MB", v.toDouble() / MB)
            // Below ten megabytes, whole megabytes cannot tell a photo from
            // its own optimised copy: 5.4 MB and 4.8 MB both printed as
            // "5 MB", so a row read "5 MB -> 5 MB (410 KB smaller)" - the
            // same number twice, followed by a claim that it changed. One
            // decimal keeps before and after apart at the sizes photos are.
            v >= MB -> String.format(Locale.US, "%.1f MB", v.toDouble() / MB)
            v >= KB -> String.format(Locale.US, "%.0f KB", v.toDouble() / KB)
            else -> "$v B"
        }
    }

    /** Always GB, two decimals - for the calculator, where units must match. */
    fun gb(value: Double): String = String.format(Locale.US, "%.2f GB", value)

    /** Counts with thousands separators, in the phone's locale. */
    fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    /** Whole percentages only; nobody needs 63.4% of a photo. */
    fun percent(fraction: Double): String =
        "${(fraction * 100).coerceIn(0.0, 100.0).toInt()}%"

    fun percentOf(part: Long, whole: Long): String =
        if (whole <= 0) "0%" else percent(part.toDouble() / whole)

    /** "1 h 24 min", "24 min", "45 s". */
    fun duration(ms: Long): String {
        if (ms <= 0) return "0 min"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            totalMinutes > 0 -> "$totalMinutes min"
            else -> "${ms / 1000} s"
        }
    }

    /** Hours as the calculator states them: "3 h 20 min". */
    fun hours(value: Double): String = duration((value * 3_600_000).toLong())

    /**
     * A settings chip's label.
     *
     * Formatted through [bytes] rather than by its own rule, so a chip and
     * every later report of the same limit are the same string by
     * construction. They used to drift: chips counted binary megabytes while
     * sizes were printed decimal, and "500 MB" came back as "524 MB".
     */
    fun mbLabel(mb: Int): String = if (mb < 0) "" else bytes(mb.toLong() * MB)

    fun dateTime(ms: Long): String =
        if (ms <= 0) "-"
        else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun time(ms: Long): String =
        if (ms <= 0) "-"
        else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun date(ms: Long): String =
        if (ms <= 0) "-"
        else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

    /**
     * The local calendar date, as the key daily budgets reset on.
     *
     * LocalDate rather than elapsed-24h arithmetic: on the days a timezone or
     * DST changes, "24 hours since" and "a new day" are not the same thing,
     * and the budget should follow the calendar the user sees.
     */
    fun dayKey(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** Local midnight at the start of [ms]'s day. */
    fun startOfDay(ms: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Local midnight after [ms] - when a day-keyed budget frees up again. */
    fun nextMidnight(ms: Long): Long {
        val zone = ZoneId.systemDefault()
        val tomorrow = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().plusDays(1)
        return tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Whole days between two instants, by local calendar date. */
    fun daysBetween(fromMs: Long, toMs: Long): Int {
        val zone = ZoneId.systemDefault()
        val a = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        return (b.toEpochDay() - a.toEpochDay()).toInt()
    }

    /** "2026-08" and "2026", for grouping a long list by when things happened. */
    fun monthKey(ms: Long): String =
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ms))

    fun yearKey(ms: Long): String =
        SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(ms))

    fun localDate(ms: Long): LocalDate =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
}
