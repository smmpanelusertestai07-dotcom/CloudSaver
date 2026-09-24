package com.pocketide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.pocketide.core.Channels

/**
 * What runs once per process start: notification channels, the periodic jobs (sync, daily
 * maintenance, discovery, update checks) and the access checks. Kept apart from [PocketApp] so
 * the wiring lives in one place.
 */
object AppStartup {
    fun onCreate(graph: AppGraph) {
        createChannels(graph.context)
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(Channels.ENGINE, context.getString(R.string.engine_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.engine_channel_description) },
            NotificationChannel(Channels.SYNC, context.getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.sync_channel_description) },
            NotificationChannel(Channels.AGENTS, context.getString(R.string.agents_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.agents_channel_description) },
            NotificationChannel(Channels.BUILDS, context.getString(R.string.builds_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.builds_channel_description) },
        )
        manager.createNotificationChannels(channels)
    }
}
