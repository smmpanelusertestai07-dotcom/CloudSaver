package app.cloudsaver.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.RunDecider
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.util.Storage
import app.cloudsaver.util.Volumes

/**
 * Reads the live device state for [RunDecider] and checks the resource gates
 * (storage volume, free space, app budget) that are independent of power.
 */
object Gates {

    /**
     * Snapshot of battery/screen/thermal. [lastInteractiveAt] is the last time
     * the app observed the screen ON (activity lifecycle or a worker tick); it
     * is the best estimate available without an implicit broadcast receiver.
     */
    fun readPower(context: Context, lastInteractiveAt: Long, now: Long): RunDecider.Power {
        var plugged = false
        var pct = 100
        var temp = 0
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) pct = level * 100 / scale
                // AC, USB, wireless and dock all count as charging.
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            }
        } catch (e: Exception) {
            // Treat an unreadable battery as "on battery, full" - the other
            // gates still apply and nothing dangerous happens.
        }

        var interactive = false
        var thermal = false
        var saver = false
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            interactive = pm.isInteractive
            saver = pm.isPowerSaveMode
            thermal = pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } catch (e: Exception) {
            // Some OEMs throw on thermal queries; keep the safe defaults.
        }

        val screenOffMs = if (interactive) 0L else (now - lastInteractiveAt).coerceAtLeast(0L)
        return RunDecider.Power(
            plugged = plugged,
            batteryPct = pct,
            batteryTempTenthsC = temp,
            saverOn = saver,
            screenInteractive = interactive,
            screenOffMs = screenOffMs,
            thermalThrottled = thermal
        )
    }

    /** Storage-side stop reasons; null means there is room to work. */
    fun resourceGate(
        context: Context,
        o: Options,
        stageBytes: Long,
        releasedBytes: Long
    ): String? {
        if (o.storageVolume.isNotEmpty() && Volumes.byName(context, o.storageVolume) == null) {
            return "volume_missing"
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
