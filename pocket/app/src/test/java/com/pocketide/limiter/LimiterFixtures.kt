package com.pocketide.limiter

import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal

const val GB_BYTES = 1024L * 1024 * 1024

/** A 4 GB phone as it reports itself to apps (about 3.6 GB), cool, half charged, plenty free. */
val FOUR_GB_TOTAL = (3.6 * GB_BYTES).toLong()
val EIGHT_GB_TOTAL = (7.5 * GB_BYTES).toLong()

fun phone(
    total: Long = FOUR_GB_TOTAL,
    free: Long = 2 * GB_BYTES,
    battery: Int = 60,
    charging: Boolean = false,
    thermal: Thermal = Thermal.NONE,
    processes: Int = 4,
    lowMemory: Boolean = false,
) = PhoneSnapshot.UNKNOWN.copy(
    at = 1_000,
    totalRamBytes = total,
    availRamBytes = free,
    lowMemory = lowMemory,
    batteryPercent = battery,
    charging = charging,
    thermal = thermal,
    processCount = processes,
)

val NAMES: (String) -> String = { id -> id.replaceFirstChar { it.uppercase() } }
