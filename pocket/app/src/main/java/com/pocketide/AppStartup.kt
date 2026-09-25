package com.pocketide

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.pocketide.agents.AgentWorkers
import com.pocketide.core.Channels
import com.pocketide.linux.ComputerUpdateWorker
import kotlinx.coroutines.launch

/**
 * What runs once per process start: notification channels, the periodic jobs (sync, daily
 * maintenance, update checks, computer updates, agent discovery and updates, access checks),
 * the phone monitor and the rooms' phone-bridge handlers. Kept apart from [PocketApp] so the wiring lives in one place.
 *
 * Only the channels are made on the main thread; everything else touches disk or builds modules,
 * so it runs on the graph's background scope.
 */
object AppStartup {
    private const val TAG = "PocketStartup"

    fun onCreate(graph: AppGraph) {
        createChannels(graph.context)
        graph.scope.launch { runEach(steps(graph)) { name, error -> Log.w(TAG, "Start-up step '$name' failed.", error) } }
    }

    /**
     * Every job is unique work with KEEP, so a second process start changes nothing. The limiter
     * and the access checks come first: they are what keeps the owner safe.
     */
    private fun steps(graph: AppGraph): List<Step> = listOf(
        Step("limiter") { graph.limiter.start() },
        Step("access checks") { graph.access.startPeriodicChecks() },
        Step("sync") { graph.sync.schedule() },
        Step("computer updates") { ComputerUpdateWorker.schedule(graph.context) },
        Step("app updates") { graph.updater.schedule() },
        Step("agent discovery and updates") { AgentWorkers.schedule(graph.context) },
        // Rooms register their "mcp" and "notify" phone-bridge handlers when created, which must
        // happen before a scheduled headless run opens a room.
        Step("rooms") { graph.rooms.states },
        // Loading the saved tasks puts each enabled one back into WorkManager after a restore.
        Step("scheduled tasks") { graph.schedules },
    )

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

internal class Step(val name: String, val run: () -> Unit)

/** Runs every step in order; one module failing to come up never keeps the others from starting. */
internal fun runEach(steps: List<Step>, onFailure: (name: String, error: Throwable) -> Unit) {
    for (step in steps) {
        try {
            step.run()
        } catch (e: Exception) {
            onFailure(step.name, e)
        }
    }
}
