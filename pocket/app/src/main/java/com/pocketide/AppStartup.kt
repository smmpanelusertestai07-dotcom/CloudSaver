package com.pocketide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.pocketide.core.Channels

/** What runs once per process start: only the notification channel. There are no background jobs. */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        createChannels(graph.context)
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val computer = NotificationChannel(Channels.COMPUTER, context.getString(R.string.computer_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { description = context.getString(R.string.computer_channel_description) }
        manager.createNotificationChannel(computer)
    }
}
