package com.pocketide.lock

import androidx.fragment.app.FragmentActivity
import com.pocketide.model.AccessState
import kotlinx.coroutines.flow.StateFlow

/**
 * GitHub and Drive are required. Checked at start, every 15 minutes and on each sync: revoked
 * access locks the app (the running step finishes, unsynced data is held encrypted); offline
 * never locks. Also holds the lease lock and the storage-full lock.
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
}
