package app.cloudsaver.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formats {

    fun bytes(v: Long): String {
        if (v < 0) return "0 B"
        val kb = 1024.0
        return when {
            v >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", v / (kb * kb * kb))
            v >= kb * kb -> String.format(Locale.US, "%.1f MB", v / (kb * kb))
            v >= kb -> String.format(Locale.US, "%.0f KB", v / kb)
            else -> "$v B"
        }
    }

    fun mbLabel(mb: Int): String = when {
        mb < 0 -> ""
        mb >= 1024 && mb % 1024 == 0 -> "${mb / 1024} GB"
        else -> "$mb MB"
    }

    fun dateTime(ms: Long): String =
        if (ms <= 0) "-"
        else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    fun dayKey(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}
