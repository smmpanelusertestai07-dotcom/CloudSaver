package app.cloudsaver.util

import android.content.Context
import app.cloudsaver.core.logic.DeviceDefaults
import app.cloudsaver.data.prefs.OptionsRepo

/**
 * Keeps Automatic space limits in step with the phone: a phone that fills up
 * gets the cautious daily amount, and one that is cleared out gets more room,
 * without anyone opening Settings. Run before every pass and whenever
 * Settings opens.
 */
object SpaceLimits {

    /** Works out this phone's limits, applies them while Automatic, and returns them. */
    suspend fun refresh(context: Context): DeviceDefaults.Limits {
        val repo = OptionsRepo.get(context)
        val o = repo.current()
        val limits = DeviceDefaults.automatic(
            Storage.totalBytes(context, o.storageVolume),
            Storage.freeBytes(context, o.storageVolume)
        )
        if (o.spaceAuto) repo.applyAutomaticSpace(limits)
        return limits
    }
}
