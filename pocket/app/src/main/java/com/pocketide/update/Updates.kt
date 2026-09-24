package com.pocketide.update

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

data class AppRelease(val version: String, val tag: String, val apkUrl: String, val apkBytes: Long, val notes: String, val publishedAt: String)

sealed interface UpdateState {
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    /** Downloaded and its signer matches this app's: ready for Android's installer. */
    data class Ready(val release: AppRelease) : UpdateState
    data class Failed(val why: String) : UpdateState
}

/**
 * The app updates itself from its own GitHub Releases (tag prefix pocketide-v): the APK is
 * downloaded (Wi-Fi by default), its signing certificate is compared with this app's, and
 * Android's installer is opened on the owner's tap. Engine pieces (code-server, extensions, agy,
 * Ubuntu security fixes) update themselves inside the computer with a doctor check and rollback.
 */
interface AppUpdater {
    val state: StateFlow<UpdateState>
    suspend fun check()
    suspend fun download()
    fun install(activity: Activity)
    /** Schedules the daily check (Wi-Fi) and component updates. */
    fun schedule()
}
