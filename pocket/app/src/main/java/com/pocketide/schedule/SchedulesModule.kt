package com.pocketide.schedule

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.pocketide.AppGraph
import com.pocketide.builds.BuildNotices
import com.pocketide.core.Clock
import com.pocketide.linux.ComputerState
import com.pocketide.model.SessionRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

fun createSchedules(graph: AppGraph): Schedules {
    lateinit var schedules: TaskSchedules
    val runner = ScheduledRun(GraphRunPorts(graph) { schedules })
    schedules = TaskSchedules(
        file = graph.dirs.schedules,
        scheduler = ScheduleWork.Manager(graph.context),
        powerAndWifi = AndroidPowerAndWifi(graph.context),
        io = Dispatchers.IO,
        runner = runner,
    )
    graph.scope.launch {
        try {
            schedules.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The screen loads again when it saves or runs a task.
        }
    }
    return schedules
}

private class GraphRunPorts(private val graph: AppGraph, private val schedules: () -> TaskSchedules) : RunPorts {
    override val clock: Clock get() = graph.clock

    override suspend fun startSession(projectId: String, agentId: String, title: String): SessionRecord =
        graph.sessions.start(projectId, agentId, title)

    override fun session(sessionId: String): SessionRecord? = graph.sessions.all.value.firstOrNull { it.id == sessionId }

    override suspend fun runInRoom(
        agentId: String,
        projectId: String,
        argv: List<String>,
        workDir: String,
        programEnv: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val state = graph.computer.state.value
        if (state !is ComputerState.Ready && state !is ComputerState.Updating) throw ScheduleException("The computer is not ready. Open PocketIDE to finish setting it up.")
        return try {
            graph.rooms.runHeadless(agentId, projectId, argv, workDir, programEnv, onLine)
        } catch (failed: IllegalStateException) {
            throw ScheduleException(failed.message ?: CANNOT_RUN)
        } catch (failed: IllegalArgumentException) {
            throw ScheduleException(failed.message ?: CANNOT_RUN)
        } catch (failed: IOException) {
            throw ScheduleException("The agent's room could not be prepared: ${failed.message ?: CANNOT_RUN}")
        }
    }

    override fun heavyWorkRefusal(): String? = graph.limiter.canStartHeavyWork("A scheduled task").let { if (it.allowed) null else it.reason }

    override suspend fun saveOutput(sessionId: String, file: File) {
        graph.media.add(sessionId, file, "scheduled-task-output.txt", "agent")
    }

    override suspend fun afterRun(sessionId: String) {
        try {
            graph.sessions.autosave(sessionId)
            graph.sessions.refresh()
            graph.sync.requestSync("scheduled task")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Autosave tries again later; the session is on the phone either way.
        }
    }

    override fun scratchFile(): File = File(graph.dirs.downloads, "task-${UUID.randomUUID()}.txt")

    override fun notify(taskId: String, heading: String, text: String) =
        BuildNotices.notify(graph.context, taskId.hashCode(), heading, text)

    override suspend fun recordStart(taskId: String, at: Long, sessionId: String) = schedules().recordStart(taskId, at, sessionId)

    override suspend fun recordRun(taskId: String, at: Long, sessionId: String) = schedules().recordRun(taskId, at, sessionId)

    private companion object {
        const val CANNOT_RUN = "The agent's room could not run the task."
    }
}

/** Android's own view of the charger and the network, read at the moment of asking. */
private class AndroidPowerAndWifi(private val context: Context) : PowerAndWifi {
    override fun whyNot(): String? {
        val charging = charging()
        val wifi = unmetered()
        return when {
            !charging && !wifi -> "The phone is not charging and not on Wi-Fi."
            !charging -> "The phone is not charging."
            !wifi -> "The phone is not on Wi-Fi."
            else -> null
        }
    }

    private fun charging(): Boolean {
        val battery: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun unmetered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }
}
