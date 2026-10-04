package app.cloudsaver.util

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import app.cloudsaver.core.logic.PhotoSpec

/**
 * How much a photo may be decoded to on this phone.
 *
 * A 50 MP photo held as a bitmap is 200 MB. On a 4 GB phone that is enough
 * for Android to end the app mid-photo, and the same photo would then be
 * tried again on the next run, and the next. So a phone with 4 GB or less
 * never decodes past 24 MP, whatever the setting says; a phone the app has
 * seen run out of memory mid-photo comes down further, a step at a time
 * (24, 16, 12); and while Android says memory is critical, 12 MP is the most.
 * The copy is still a fine photo - 12 MP is more than four times what a
 * phone screen shows.
 */
object DeviceTier {

    /** "4 GB" phones report about 3.7 GB; 4.5 leaves room for that. */
    const val LOW_RAM_BYTES = 4_500_000_000L

    const val LOW_END_CEILING_MP = 24
    val STEPS_MP = listOf(24, 16, 12)

    private const val PREFS = "tier"
    private const val KEY_CEILING = "ceilingMp"

    /** Set from the Application while Android reports critical memory. */
    @Volatile
    var memoryCritical: Boolean = false

    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        return am.isLowRamDevice || (info.totalMem in 1..LOW_RAM_BYTES)
    }

    /** The decode ceiling in megapixels, or 0 for none. */
    fun ceilingMp(context: Context): Int {
        val learned = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CEILING, 0)
        val base = when {
            learned > 0 -> learned
            isLowEnd(context) -> LOW_END_CEILING_MP
            else -> 0
        }
        return if (memoryCritical) minOf(if (base == 0) Int.MAX_VALUE else base, STEPS_MP.last()) else base
    }

    /** [spec] with its size held to this phone's ceiling. */
    fun fit(context: Context, spec: PhotoSpec): PhotoSpec = capped(spec, ceilingMp(context))

    fun capped(spec: PhotoSpec, ceiling: Int): PhotoSpec =
        if (ceiling > 0 && (spec.maxMp <= 0 || spec.maxMp > ceiling)) spec.copy(maxMp = ceiling) else spec

    /** One step down after the app ran out of memory on a photo; never below 12 MP. */
    fun lowerCeiling(context: Context): Int {
        val now = ceilingMp(context).takeIf { it > 0 } ?: Int.MAX_VALUE
        val next = STEPS_MP.firstOrNull { it < now } ?: STEPS_MP.last()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_CEILING, next) }
        return next
    }
}
