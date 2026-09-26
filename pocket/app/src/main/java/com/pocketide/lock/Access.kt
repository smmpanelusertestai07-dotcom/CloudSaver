package com.pocketide.lock

import androidx.fragment.app.FragmentActivity
import com.pocketide.model.AccessState
import kotlinx.coroutines.flow.StateFlow

/**
 * GitHub and Drive are required. Checked when the app opens or comes back to the front, with
 * every sync that goes to the network, before a scheduled task runs, and every 6 hours in the
 * background: revoked access locks the app (the running step finishes, unsynced data is held
 * encrypted); offline never locks. Also holds the lease lock and the storage-full lock.
 */
interface AccessGuard {
    val state: StateFlow<AccessState>
    suspend fun check()
    fun startPeriodicChecks()
}

/** The fail-closed app lock: fingerprint or screen lock; FLAG_SECURE hides the app in recents. */
interface AppLock {
    val unlocked: StateFlow<Boolean>
    fun lockNow()
    fun onBackground()
    fun onForeground()
    /** Shows Android's own prompt; [onDone] gets true when the owner passed it. */
    fun authenticate(activity: FragmentActivity, title: String, onDone: (Boolean) -> Unit)
    /** True when the phone has a screen lock (required). */
    fun deviceSecure(): Boolean

    /**
     * The app is sending the owner out on purpose (a settings page, a sign-in in Chrome) and
     * expects them back: the lock waits a little longer than usual before it closes again.
     */
    fun leavingOnErrand() = Unit

    /**
     * A fresh install holds nothing to protect yet: set-up opens without a prompt, and from then on
     * the lock counts absences as always (the Drive step asks for a screen lock before the key).
     */
    fun openForSetUp() = Unit
}
