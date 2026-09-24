package com.pocketide.ui.manage

import com.pocketide.ui.components.Tone

/** The facts about this phone that §2's minimum and recommended requirements are checked against. */
data class PhoneFacts(
    val androidSdk: Int,
    val androidRelease: String,
    val arm64: Boolean,
    /** Null when it could not be checked. */
    val playServices: Boolean?,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val screenLock: Boolean?,
)

data class RequirementCheck(
    val label: String,
    val minimum: String,
    val recommended: String,
    val thisPhone: String,
    /** OK: meets the recommendation; WARN: meets the minimum only; ERROR: below the minimum. */
    val tone: Tone,
)

object PhoneRequirements {
    private const val GIB = 1L shl 30
    private const val GB = 1_000_000_000L

    /*
     * Android reports less memory than the box says (the kernel and modem keep some): a "4 GB"
     * phone shows about 3.6 GiB, a "6 GB" one about 5.5 GiB. The limits are the marketed sizes
     * expressed the way Android reports them.
     */
    private const val RAM_MIN = (3.2 * GIB).toLong()
    private const val RAM_RECOMMENDED = (5.2 * GIB).toLong()
    private const val STORAGE_MIN = 8 * GB
    private const val STORAGE_RECOMMENDED = 16 * GB
    private const val SDK_MIN = 29
    private const val SDK_RECOMMENDED = 31

    fun check(f: PhoneFacts): List<RequirementCheck> = listOf(
        RequirementCheck(
            "Android", "10, 64-bit", "12 or newer", "Android ${f.androidRelease}",
            graded(f.androidSdk >= SDK_MIN, f.androidSdk >= SDK_RECOMMENDED),
        ),
        RequirementCheck(
            "Processor", "64-bit (arm64)", "64-bit (arm64)", if (f.arm64) "64-bit (arm64)" else "32-bit only",
            if (f.arm64) Tone.OK else Tone.ERROR,
        ),
        RequirementCheck(
            "Memory", "4 GB", "6 GB or more",
            if (f.totalRamBytes > 0) ManageFormat.bytes(f.totalRamBytes) else "Unknown",
            if (f.totalRamBytes <= 0) Tone.NEUTRAL else graded(f.totalRamBytes >= RAM_MIN, f.totalRamBytes >= RAM_RECOMMENDED),
        ),
        RequirementCheck(
            "Free storage", "8 GB", "16 GB", "${ManageFormat.bytes(f.freeStorageBytes)} free",
            graded(f.freeStorageBytes >= STORAGE_MIN, f.freeStorageBytes >= STORAGE_RECOMMENDED),
        ),
        RequirementCheck(
            "Screen lock", "PIN, pattern or password", "Fingerprint too",
            when (f.screenLock) { true -> "On"; false -> "Off"; null -> "Not checked" },
            when (f.screenLock) { true -> Tone.OK; false -> Tone.ERROR; null -> Tone.NEUTRAL },
        ),
        RequirementCheck(
            "Google Play services", "Required", "Required",
            when (f.playServices) { true -> "Available"; false -> "Missing"; null -> "Not checked" },
            when (f.playServices) { true -> Tone.OK; false -> Tone.ERROR; null -> Tone.NEUTRAL },
        ),
    )

    fun summary(checks: List<RequirementCheck>): Told = when {
        checks.any { it.tone == Tone.ERROR } -> Told("This phone is below the minimum.", Tone.ERROR)
        checks.any { it.tone == Tone.WARN } -> Told("This phone meets the minimum.", Tone.WARN)
        else -> Told("This phone meets the recommendation.", Tone.OK)
    }

    private fun graded(minimum: Boolean, recommended: Boolean): Tone = when {
        !minimum -> Tone.ERROR
        !recommended -> Tone.WARN
        else -> Tone.OK
    }
}
