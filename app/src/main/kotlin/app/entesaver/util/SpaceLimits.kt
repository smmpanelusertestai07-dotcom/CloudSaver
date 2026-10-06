package app.entesaver.util

import android.content.Context
import app.entesaver.core.logic.DeviceDefaults
import app.entesaver.data.prefs.OptionsRepo

/**
 * Keeps Automatic space limits in step with the phone: a phone that fills up
 * gets the cautious daily amount, and one that is cleared out gets more room,
 * without anyone opening Settings. Run before a pass while the limits are
 * Automatic, and whenever Settings opens. Only Automatic's own figures are
 * written; the person's Custom ones are never touched.
 */
object SpaceLimits {

    /** Works out this phone's limits, stores them as Automatic's figures, and returns them. */
    suspend fun refresh(context: Context): DeviceDefaults.Limits {
        val repo = OptionsRepo.get(context)
        val volume = repo.current().storageVolume
        // One look at the volume gives both figures.
        val vol = Volumes.selected(context, volume)
        val limits = DeviceDefaults.automatic(
            vol?.totalBytes ?: Storage.totalBytes(context, volume),
            vol?.freeBytes ?: Storage.freeBytes(context, volume)
        )
        repo.applyAutomaticSpace(limits)
        return limits
    }
}
