package com.pocketide.ui.shell

import com.pocketide.core.ThemeMode

/** One option of a setting; [isDefault] marks the plan's default so the screen can say so. */
data class Choice<T>(val value: T, val label: String, val isDefault: Boolean = false)

/**
 * The choices Settings offers, with the plan's defaults (§6.7, §6.8). Kept here, outside the
 * composables, so a test can hold them against [com.pocketide.core.Settings]'s defaults.
 */
object SettingChoices {
    private const val GB = 1_000_000_000L

    /** Always left free on the phone, whatever PocketIDE's own limit is (§6.5). */
    const val PHONE_RESERVE_GB = 2

    val theme = listOf(
        Choice(ThemeMode.SYSTEM, "Same as phone", isDefault = true),
        Choice(ThemeMode.LIGHT, "Light"),
        Choice(ThemeMode.DARK, "Dark"),
    )

    val dailyMobileLimitMb = listOf(
        Choice(0, "Off: never use mobile data"),
        Choice(200, "200 MB a day", isDefault = true),
        Choice(500, "500 MB a day"),
        Choice(1000, "1 GB a day"),
        Choice(2000, "2 GB a day"),
        Choice(5000, "5 GB a day"),
    )

    val maxAgents = listOf(
        Choice(0, "Auto: from this phone's memory", isDefault = true),
        Choice(1, "1 at a time"),
        Choice(2, "2 at a time"),
        Choice(3, "3 at a time"),
    )

    private val phoneLimitsGb = listOf(4, 8, 16, 32, 64, 128)

    val driveLimitGb = listOf(
        Choice(1, "1 GB"),
        Choice(2, "2 GB", isDefault = true),
        Choice(5, "5 GB"),
        Choice(10, "10 GB"),
        Choice(20, "20 GB"),
        Choice(50, "50 GB"),
        Choice(100, "100 GB"),
    )

    val keepChatsMonths = listOf(
        Choice(0, "Until I delete", isDefault = true),
        Choice(3, "3 months after the last message"),
        Choice(6, "6 months after the last message"),
        Choice(12, "12 months after the last message"),
        Choice(18, "18 months after the last message"),
        Choice(36, "36 months after the last message"),
    )

    val phoneChatDays = listOf(
        Choice(30, "30 days after Drive has them", isDefault = true),
        Choice(90, "90 days after Drive has them"),
        Choice(-1, "Keep all on this phone"),
    )

    val phoneMediaDays = listOf(
        Choice(30, "30 days, then downloaded again when you open the chat", isDefault = true),
        Choice(-1, "Keep all on this phone (for offline use)"),
    )

    val cacheDays = listOf(
        Choice(30, "After 30 days unused", isDefault = true),
        Choice(14, "After 14 days unused"),
    )

    val computerUnusedDays = listOf(
        Choice(90, "After 90 days without agent work", isDefault = true),
        Choice(-1, "Never"),
    )

    /**
     * Phone limits this phone can actually give while keeping [PHONE_RESERVE_GB] free. The
     * current value always stays in the list so a setting from another phone is never hidden.
     */
    fun phoneLimitGb(storageTotalBytes: Long, current: Int): List<Choice<Int>> {
        val fits = phoneLimitsGb.filter { gb ->
            storageTotalBytes <= 0 || (gb + PHONE_RESERVE_GB) * GB <= storageTotalBytes
        }
        val values = (fits + current).distinct().sorted()
        return values.map { Choice(it, "$it GB", isDefault = it == 8) }
    }

    /** The label of [value], or a plain rendering when it is not one of the offered choices. */
    fun <T> labelOf(choices: List<Choice<T>>, value: T, fallback: (T) -> String = { it.toString() }): String =
        choices.firstOrNull { it.value == value }?.label ?: fallback(value)
}
