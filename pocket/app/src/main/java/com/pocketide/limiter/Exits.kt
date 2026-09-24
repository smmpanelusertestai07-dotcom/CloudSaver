package com.pocketide.limiter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.provider.Settings

/** What the engine was doing when this process last ended, written while it runs. */
internal data class EngineTrace(val agentIds: List<String>, val bootCount: Int?)

/**
 * Why the rooms stopped last time, when nobody asked them to. The process that could have said
 * so is the one that was killed, but Android keeps its own record (ApplicationExitInfo, Android
 * 11+), and a changed boot count tells a restart apart from a kill.
 */
internal object ExitReasons {

    /** Android's record of the last exit, in the owner's words. Null when the rooms were not running. */
    fun explain(trace: EngineTrace?, bootCount: Int?, reason: Int?, description: String?, at: Long): RoomStop? {
        if (trace == null || trace.agentIds.isEmpty()) return null
        val rebooted = trace.bootCount != null && bootCount != null && bootCount != trace.bootCount
        val (cause, why) = when {
            rebooted -> StopCause.REBOOT to "The phone restarted while your agents were running."
            description.orEmpty().contains("MemoryLimiter") ->
                StopCause.MEMORY to "Android closed PocketIDE because it reached Android's memory limit for one app."
            reason == ApplicationExitInfo.REASON_LOW_MEMORY ->
                StopCause.MEMORY to "Android needed memory for other apps and closed PocketIDE."
            reason == REASON_PACKAGE_UPDATED -> StopCause.UPDATE to "PocketIDE was updated while your agents were running."
            reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == REASON_USER_STOPPED ->
                StopCause.ANDROID to "PocketIDE was stopped from the phone's settings or task manager."
            reason == ApplicationExitInfo.REASON_CRASH || reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR ->
                StopCause.ANDROID to "PocketIDE stopped unexpectedly."
            else -> StopCause.ANDROID to
                "Android or the phone's battery manager closed PocketIDE in the background. The battery steps on Home help."
        }
        return RoomStop(trace.agentIds, cause, "$why Nothing was lost.", at)
    }

    /** ApplicationExitInfo.REASON_PACKAGE_UPDATED and REASON_USER_STOPPED arrived in later Android versions. */
    private const val REASON_PACKAGE_UPDATED = 16
    private const val REASON_USER_STOPPED = 11
}

/**
 * Keeps the engine's trace in private preferences. One per process ([of]), so the limiter and
 * the engine service agree on what the last process left behind before this one overwrites it.
 */
internal class EngineRecord private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("pocketide.engine", Context.MODE_PRIVATE)

    /** The last process's trace, read before this process writes its own. */
    private val previous: EngineTrace? = read()

    fun running(agentIds: Collection<String>) {
        prefs.edit().putString(KEY_AGENTS, agentIds.sorted().joinToString(",")).putInt(KEY_BOOT, bootCount() ?: -1).apply()
    }

    fun stoppedCleanly() {
        prefs.edit().remove(KEY_AGENTS).remove(KEY_BOOT).apply()
    }

    /** The stop to explain on this start, if the rooms were running when the last process ended. */
    fun lastStop(): RoomStop? {
        val trace = previous ?: return null
        val record = if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
            }.getOrNull()
        } else {
            null
        }
        val at = record?.timestamp ?: System.currentTimeMillis()
        return ExitReasons.explain(trace, bootCount(), record?.reason, record?.description, at)
    }

    private fun read(): EngineTrace? {
        val agents = prefs.getString(KEY_AGENTS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (agents.isEmpty()) return null
        return EngineTrace(agents, prefs.getInt(KEY_BOOT, -1).takeIf { it >= 0 })
    }

    private fun bootCount(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    companion object {
        private const val KEY_AGENTS = "running.agents"
        private const val KEY_BOOT = "running.boot"

        @Volatile private var instance: EngineRecord? = null

        fun of(context: Context): EngineRecord = instance ?: synchronized(this) {
            instance ?: EngineRecord(context.applicationContext).also { instance = it }
        }
    }
}
