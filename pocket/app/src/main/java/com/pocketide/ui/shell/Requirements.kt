package com.pocketide.ui.shell

import com.pocketide.ui.components.Tone

/** What the Welcome screen checks against the minimum requirements (§2). */
data class PhoneFacts(
    val androidSdk: Int,
    val androidRelease: String,
    val arm64: Boolean,
    /** Null when it could not be checked. */
    val playServices: Boolean?,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    val screenLock: Boolean,
)

data class RequirementRow(val label: String, val value: String, val tone: Tone, val note: String?)

object Requirements {
    private const val GIB = 1L shl 30
    private const val GB = 1_000_000_000L

    /*
     * Android reports less RAM than the box says, because the kernel and the modem reserve some:
     * a "4 GB" phone shows about 3.6-3.8 GiB, a "6 GB" one about 5.5. The limits below are the
     * marketed sizes expressed the way Android reports them.
     */
    private const val RAM_MIN = (3.2 * GIB).toLong()
    private const val RAM_RECOMMENDED = (5.2 * GIB).toLong()
    private const val STORAGE_MIN = 8 * GB
    private const val STORAGE_RECOMMENDED = 16 * GB
    private const val SDK_MIN = 29
    private const val SDK_RECOMMENDED = 31

    fun rows(facts: PhoneFacts): List<RequirementRow> = listOf(
        android(facts),
        chip(facts),
        ram(facts),
        storage(facts),
        screenLock(facts),
        playServices(facts),
    )

    /** True when nothing is below the minimum (warnings are fine). */
    fun allMet(rows: List<RequirementRow>): Boolean = rows.none { it.tone == Tone.ERROR }

    private fun android(f: PhoneFacts) = RequirementRow(
        label = "Android",
        value = "Android ${f.androidRelease}",
        tone = when {
            f.androidSdk < SDK_MIN -> Tone.ERROR
            f.androidSdk < SDK_RECOMMENDED -> Tone.WARN
            else -> Tone.OK
        },
        note = when {
            f.androidSdk < SDK_MIN -> "Needs Android 10 or newer."
            f.androidSdk < SDK_RECOMMENDED -> "Works. Android 12 or newer is smoother."
            else -> null
        },
    )

    private fun chip(f: PhoneFacts) = RequirementRow(
        label = "Processor",
        value = if (f.arm64) "64-bit (arm64)" else "32-bit only",
        tone = if (f.arm64) Tone.OK else Tone.ERROR,
        note = if (f.arm64) null else "The computer inside the app needs a 64-bit (arm64) phone.",
    )

    private fun ram(f: PhoneFacts): RequirementRow {
        val known = f.totalRamBytes > 0
        return RequirementRow(
            label = "Memory",
            value = if (known) Formats.bytes(f.totalRamBytes) else "Unknown",
            tone = when {
                !known -> Tone.NEUTRAL
                f.totalRamBytes < RAM_MIN -> Tone.ERROR
                f.totalRamBytes < RAM_RECOMMENDED -> Tone.WARN
                else -> Tone.OK
            },
            note = when {
                !known -> null
                f.totalRamBytes < RAM_MIN -> "Needs 4 GB of memory."
                f.totalRamBytes < RAM_RECOMMENDED -> "One main agent plus Antigravity at a time. 6 GB runs all three."
                else -> "All three agents can run together."
            },
        )
    }

    private fun storage(f: PhoneFacts) = RequirementRow(
        label = "Free storage",
        value = Formats.bytes(f.freeStorageBytes),
        tone = when {
            f.freeStorageBytes < STORAGE_MIN -> Tone.ERROR
            f.freeStorageBytes < STORAGE_RECOMMENDED -> Tone.WARN
            else -> Tone.OK
        },
        note = when {
            f.freeStorageBytes < STORAGE_MIN -> "Needs 8 GB free: the computer alone takes about 2.5 GB."
            f.freeStorageBytes < STORAGE_RECOMMENDED -> "Enough to start. 16 GB leaves room for projects."
            else -> null
        },
    )

    private fun screenLock(f: PhoneFacts) = RequirementRow(
        label = "Screen lock",
        value = if (f.screenLock) "On" else "Off",
        tone = if (f.screenLock) Tone.OK else Tone.WARN,
        note = if (f.screenLock) null else "Needed before Google Drive: it protects the key on this phone.",
    )

    private fun playServices(f: PhoneFacts) = RequirementRow(
        label = "Google Play services",
        value = when (f.playServices) {
            true -> "Available"
            false -> "Missing"
            null -> "Not checked"
        },
        tone = when (f.playServices) {
            true -> Tone.OK
            false -> Tone.ERROR
            null -> Tone.NEUTRAL
        },
        note = if (f.playServices == false) "Needed to connect Google Drive." else null,
    )
}
