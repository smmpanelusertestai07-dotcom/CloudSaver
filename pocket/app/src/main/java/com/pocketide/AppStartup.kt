package com.pocketide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.pocketide.core.AppFolders
import com.pocketide.core.Channels
import com.pocketide.core.OldVersionFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What runs once per process start: the notification channel, and once after an update, deleting
 * what an older version left on the phone. Nothing is scheduled to run later.
 */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        createChannels(graph.context)
        graph.scope.launch(Dispatchers.IO) {
            // Housekeeping must never stop the app from starting; what fails is tried at the next start.
            runCatching { OldVersionFiles.removeOnce(AppFolders.of(graph.context)) }
        }
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val computer = NotificationChannel(Channels.COMPUTER, context.getString(R.string.computer_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.computer_channel_description) }
        // Channels an older version made (sync, schedules, limits) have nothing left to say.
        manager.notificationChannels.filter { it.id != Channels.COMPUTER }.forEach { manager.deleteNotificationChannel(it.id) }
        manager.createNotificationChannel(computer)
    }
}
