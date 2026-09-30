package com.pocketide

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.pocketide.core.AppFolders
import com.pocketide.core.OldVersionFiles
import com.pocketide.ui.components.AgentIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.KeyStore

/**
 * What runs once per process start: once after an update, the deletion of what an older version
 * left that nothing uses now, and the agents' icons, ready before Home shows them.
 */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        graph.scope.launch(Dispatchers.IO) {
            // Housekeeping must never stop the app from starting; what fails is tried at the next start.
            val removed = runCatching { OldVersionFiles.removeOnce(AppFolders.of(graph.context)) }.getOrDefault(false)
            if (removed) removeOldSystemEntries(graph.context)
            runCatching { AgentIcons.preload(graph.context.cacheDir) }
        }
    }

    /**
     * Version 5's notification channels (the computer's "on" notice, sign-in pages) and the key it
     * sealed the owner's keys with, in the phone's secure hardware: only Android's APIs remove them.
     */
    private fun removeOldSystemEntries(context: Context) {
        runCatching {
            val notifications = NotificationManagerCompat.from(context)
            OLD_CHANNELS.forEach(notifications::deleteNotificationChannel)
        }
        runCatching { KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(OLD_KEY) }
    }

    private val OLD_CHANNELS = listOf("computer", "links")
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val OLD_KEY = "pocketide.secure.v1"
}
