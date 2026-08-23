package app.litesaver.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.litesaver.core.logic.Defaults
import app.litesaver.core.logic.Speed
import app.litesaver.data.prefs.Options
import app.litesaver.util.Storage

/**
 * Run gates checked between items: battery level/charging per Speed mode,
 * thermal state (PowerManager >= MODERATE or battery > 42 C), free space,
 * extra-space budget and stage cap.
 */
object Gates {

    data class Battery(val pct: Int, val charging: Boolean, val tempTenths: Int)

    fun battery(context: Context): Battery {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            Battery(pct, charging, temp)
        } catch (e: Exception) {
            Battery(100, true, 0)
        }
    }

    /** Null = all gates open; otherwise the reason to stop. */
    fun check(context: Context, o: Options, stageBytes: Long, releasedBytes: Long): String? {
        if (o.storageVolume.isNotEmpty() &&
            app.litesaver.util.Volumes.byName(context, o.storageVolume) == null
        ) {
            return "volume_missing"
        }
        val bat = battery(context)
        when (o.speed) {
            Speed.CHARGING_ONLY -> {
                if (!bat.charging) return "not_charging"
                if (bat.pct in 0 until Defaults.MIN_BATTERY_CHARGING) return "battery_low"
            }
            Speed.ANYTIME, Speed.INSTANT -> {
                if (!bat.charging && bat.pct in 0 until Defaults.MIN_BATTERY_ANYTIME) {
                    return "battery_low"
                }
            }
        }
        if (bat.tempTenths > Defaults.BATTERY_MAX_TEMP_TENTHS_C) return "battery_hot"
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return "thermal"
        } catch (e: Exception) {
            // Some OEMs throw here; ignore.
        }
        if (Storage.freeBytes(context, o.storageVolume) < o.minFreeBytes) return "low_space"
        val extra = stageBytes + releasedBytes
        if (o.maxExtraBytes >= 0 && extra >= o.maxExtraBytes) return "extra_full"
        if (o.dailyCapBytes >= 0 && stageBytes >= Defaults.STAGE_CAP_FACTOR * o.dailyCapBytes) {
            return "stage_full"
        }
        return null
    }
}
