package app.cloudsaver.util

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import app.cloudsaver.core.logic.PhotoSpec

/**
 * How much a photo may be decoded to on this phone, and what a very small
 * phone leaves for later.
 *
 * A 50 MP photo held as a bitmap is 200 MB. On a small phone that is enough
 * for Android to end the app mid-photo, and the same photo would then be
 * tried again on the next run, and the next. So the phone's memory sets a
 * ceiling, whatever the setting says: 12 MP on a phone with 2.5 GB or less
 * (or Android Go), 24 MP up to 4.5 GB, and 50 MP on any phone, because a
 * 200 MP photo held whole is 800 MB and Android 17 limits each app's memory
 * by the phone's total. A phone the app has seen run out of memory mid-photo
 * comes down further, a step at a time (24, 16, 12), and when memory is
 * short right now the photo is made at 12 MP. The copy is still a fine
 * photo - 12 MP is more than four times what a phone screen shows.
 */
object DeviceTier {

    /** "4 GB" phones report about 3.7 GB; 4.5 leaves room for that. */
    const val LOW_RAM_BYTES = 4_500_000_000L

    /** "2 GB" phones and the smallest "3 GB" ones. */
    const val VERY_LOW_RAM_BYTES = 2_500_000_000L

    const val LOW_END_CEILING_MP = 24
    const val VERY_LOW_CEILING_MP = 12
    const val ANY_PHONE_CEILING_MP = 50
    val STEPS_MP = listOf(24, 16, 12)

    private const val PREFS = "tier"
    private const val KEY_CEILING = "ceilingMp"

    enum class Tier { VERY_LOW, LOW, NORMAL }

    /** Set from the Application while Android reports critical memory (before Android 14). */
    @Volatile
    var memoryCritical: Boolean = false

    fun tierFor(totalMemBytes: Long, lowRamDevice: Boolean): Tier = when {
        lowRamDevice || totalMemBytes in 1..VERY_LOW_RAM_BYTES -> Tier.VERY_LOW
        totalMemBytes in 1..LOW_RAM_BYTES -> Tier.LOW
        else -> Tier.NORMAL
    }

    fun baseCeilingMp(tier: Tier): Int = when (tier) {
        Tier.VERY_LOW -> VERY_LOW_CEILING_MP
        Tier.LOW -> LOW_END_CEILING_MP
        Tier.NORMAL -> ANY_PHONE_CEILING_MP
    }

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        runCatching { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) }
        return info
    }

    /** The phone's memory and low-RAM flag never change, so they are asked once per process. */
    @Volatile
    private var fixed: Pair<Long, Boolean>? = null

    private fun fixedFacts(context: Context): Pair<Long, Boolean> = fixed ?: run {
        val am = context.getSystemService(ActivityManager::class.java)
        val facts = memoryInfo(context).totalMem to (am?.isLowRamDevice == true)
        if (facts.first > 0) fixed = facts
        facts
    }

    /** The phone's memory, as Android reports it (a "4 GB" phone says about 3.7 GB). */
    fun totalMemBytes(context: Context): Long = fixedFacts(context).first

    fun tier(context: Context): Tier {
        val (total, lowRam) = fixedFacts(context)
        return tierFor(total, lowRam)
    }

    /** The lasting ceiling in megapixels: the phone's memory, then what this phone has taught the app. */
    fun lastingCeilingMp(context: Context): Int {
        val learned = learnedCeilingMp(context)
        val base = baseCeilingMp(tier(context))
        return if (learned > 0) minOf(learned, base) else base
    }

    /**
     * The size the photo about to be made at [targetMp] can have, given the
     * memory free right now: decoding and encoding hold about two bitmaps of
     * the output size, and Android's own low-memory line must stay clear of
     * that. A step at a time (24, 16, 12), never below 12 MP.
     */
    fun fitToMemory(targetMp: Int, availBytes: Long, thresholdBytes: Long, lowMemory: Boolean): Int {
        val floor = STEPS_MP.last()
        if (targetMp <= floor) return targetMp
        if (lowMemory) return floor
        val room = availBytes - thresholdBytes
        fun fits(mp: Int) = 2L * mp * 1_000_000L * 4L <= room
        if (fits(targetMp)) return targetMp
        return STEPS_MP.firstOrNull { it < targetMp && fits(it) } ?: floor
    }

    /**
     * [spec] held to this phone's lasting ceiling, then to the memory free
     * right now - which counts as short while Android has said memory is
     * critical (before Android 14).
     */
    fun fit(context: Context, spec: PhotoSpec): PhotoSpec {
        val held = capped(spec, lastingCeilingMp(context))
        val info = memoryInfo(context)
        if (info.totalMem <= 0) {
            return if (memoryCritical) capped(held, STEPS_MP.last()) else held
        }
        return capped(held, fitToMemory(held.maxMp, info.availMem, info.threshold, info.lowMemory || memoryCritical))
    }

    fun capped(spec: PhotoSpec, ceiling: Int): PhotoSpec =
        if (ceiling > 0 && (spec.maxMp <= 0 || spec.maxMp > ceiling)) spec.copy(maxMp = ceiling) else spec

    /** One step down after the app ran out of memory on a photo; never below 12 MP. */
    fun lowerCeiling(context: Context): Int {
        val now = lastingCeilingMp(context)
        val next = STEPS_MP.firstOrNull { it < now } ?: STEPS_MP.last()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_CEILING, next) }
        return next
    }

    /** Whether the app has lowered this phone's ceiling after a photo ran it out of memory. */
    fun learnedCeilingMp(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CEILING, 0)
}
