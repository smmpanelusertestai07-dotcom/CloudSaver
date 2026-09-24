package com.pocketide.model

/** Android's thermal status, in order of severity. */
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** Everything the limiter and the phone strip need, read at one moment. */
data class PhoneSnapshot(
    val at: Long,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowMemory: Boolean,
    /** This app plus its Linux processes (PSS), when Android lets us read it. */
    val appPssBytes: Long,
    val thermal: Thermal,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    /** Everything under the app's private storage (computer, projects, chats, caches). */
    val appDataBytes: Long,
    /** Processes this app runs (Android 12+ kills beyond 32 phantom processes). */
    val processCount: Int,
    val online: Boolean,
    val metered: Boolean,
    val dataSaver: Boolean,
    /** UsageStatsManager bucket; 45 = restricted. Null when unknown. */
    val standbyBucket: Int?,
    val backgroundRestricted: Boolean,
) {
    companion object {
        val UNKNOWN = PhoneSnapshot(
            at = 0, totalRamBytes = 0, availRamBytes = 0, lowMemory = false, appPssBytes = 0,
            thermal = Thermal.NONE, batteryPercent = 100, charging = false, powerSave = false,
            storageFreeBytes = 0, storageTotalBytes = 0, appDataBytes = 0, processCount = 0,
            online = true, metered = false, dataSaver = false, standbyBucket = null,
            backgroundRestricted = false,
        )
    }
}

/** What the limiter currently allows, from least to most restrictive. */
enum class Guard {
    OK,
    /** Battery ≤ 20 % or heat moderate: no new heavy work (builds, installs, new agents). */
    NO_NEW_HEAVY,
    /** Not enough memory for another agent: idle agents close first. */
    NO_NEW_AGENTS,
    /** Battery ≤ 10 % or heat severe: finish the current step, sync, then pause. */
    PAUSE,
    /** Battery ≤ 5 % or heat critical: stop safely now. */
    SAFE_STOP,
}

/** The answer to "may this start now?", with a reason the owner can read. */
data class Decision(val allowed: Boolean, val reason: String? = null) {
    companion object {
        val YES = Decision(true)
        fun no(reason: String) = Decision(false, reason)
    }
}
