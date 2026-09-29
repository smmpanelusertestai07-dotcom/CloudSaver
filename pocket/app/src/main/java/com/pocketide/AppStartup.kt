package com.pocketide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.pocketide.core.AppFolders
import com.pocketide.core.Channels
import com.pocketide.core.OldVersionFiles
import com.pocketide.ide.IdeState
import com.pocketide.ui.components.AgentIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What runs once per process start: the notification channels, the agents' icons, once after an
 * update the deletion of what an older version left on the phone, and, while the computer is on,
 * the daily updates.
 */
object AppStartup {
    private const val HOUR_MS = 60 * 60 * 1000L

    fun onCreate(graph: AppGraph) {
        createChannels(graph.context)
        graph.scope.launch(Dispatchers.IO) {
            // Housekeeping must never stop the app from starting; what fails is tried at the next start.
            val removed = runCatching { OldVersionFiles.removeOnce(AppFolders.of(graph.context)) }.getOrDefault(false)
            // An older version's web page kept a GitHub sign-in in the WebView's own storage.
            if (removed) withContext(Dispatchers.Main) { clearWebStorage() }
            graph.startupDone.complete(Unit)
            // The agents' icons, ready before Home shows them.
            runCatching { AgentIcons.preload(graph.context.cacheDir) }
        }
        dailyUpdates(graph)
    }

    /** While code-server runs, the updates are checked every hour and run once a day. */
    private fun dailyUpdates(graph: AppGraph) {
        var loop: Job? = null
        graph.scope.launch {
            graph.ide.state.distinctUntilChangedBy { it is IdeState.On }.collect { state ->
                loop?.cancel()
                loop = if (state is IdeState.On) {
                    graph.scope.launch {
                        while (isActive) {
                            runCatching { graph.updater.runIfDue() }
                            delay(HOUR_MS)
                        }
                    }
                } else {
                    null
                }
            }
        }
    }

    private fun clearWebStorage() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val computer = NotificationChannel(Channels.COMPUTER, context.getString(R.string.computer_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.computer_channel_description) }
        val links = NotificationChannel(Channels.LINKS, context.getString(R.string.links_channel_name), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.links_channel_description) }
        // Channels an older version made (sync, schedules, limits) have nothing left to say.
        val ours = setOf(Channels.COMPUTER, Channels.LINKS)
        manager.notificationChannels.filter { it.id !in ours }.forEach { manager.deleteNotificationChannel(it.id) }
        manager.createNotificationChannels(listOf(computer, links))
    }
}
