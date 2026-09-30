package com.pocketide

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.pocketide.core.AppFolders
import com.pocketide.core.OldVersionFiles
import com.pocketide.linux.ComputerState
import com.pocketide.linux.UpdateOutcome
import com.pocketide.ui.components.AgentIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.KeyStore

/**
 * What runs once per process start: once after an update, the deletion of what an older version
 * left that nothing uses now; the agents' icons, ready before Home shows them; the opener of pages
 * gcloud asks for; and, once a day, the updates of PocketIDE's connection (Ubuntu's security fixes
 * and Google's gcloud, by its own updater).
 */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        graph.housekeeping = graph.scope.launch(Dispatchers.IO) {
            // Housekeeping must never stop the app from starting; what fails is tried at the next start.
            val removed = runCatching { OldVersionFiles.removeOnce(AppFolders.of(graph.context)) }.getOrDefault(false)
            if (removed) removeOldSystemEntries(graph.context)
        }
        graph.scope.launch(Dispatchers.IO) {
            runCatching { AgentIcons.preload(graph.context.cacheDir) }
            graph.housekeeping?.join()
            runCatching { graph.linkOpener.start() }
            updateConnection(graph)
        }
    }

    /** Ubuntu's updates and gcloud's own updater, at most once a day, when the connection is set up. */
    private suspend fun updateConnection(graph: AppGraph) {
        val computer = graph.computer
        if (computer.state.value != ComputerState.Ready) return
        val last = runCatching { computer.info().updatedAt }.getOrNull() ?: 0L
        if (graph.clock.now() - last < DAY_MS) return
        // Right after a start the owner may be opening an agent: the connection goes first.
        delay(UPDATE_DELAY_MS)
        val outcome = runCatching { computer.updateBase() }.getOrNull()
        if (outcome is UpdateOutcome.Updated) graph.link.gcloudUpdated()
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val UPDATE_DELAY_MS = 2 * 60 * 1000L

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
