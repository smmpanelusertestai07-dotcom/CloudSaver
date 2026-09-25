package com.pocketide.limiter

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketide.graph
import com.pocketide.rooms.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The special-use foreground service that keeps the computer alive while agents run or the
 * computer itself is being set up, reset, repaired or updated, and only then: it stops itself
 * once none of that is left. Its notification lists the running agents and the computer's work,
 * and holds "Stop everything". A partial wake lock is held only while an agent or the computer
 * is working, and always with a timeout, so a stuck "working" can never keep the phone awake
 * for long.
 *
 * specialUse, not dataSync: dataSync is limited to 6 hours a day from Android 15 and cannot
 * start after a reboot; specialUse has neither limit.
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's own action: a plain start, so no promotion is owed.
        if (intent?.action == ACTION_STOP_ALL) {
            stopEverything()
            return START_NOT_STICKY
        }
        // Android requires this promptly after startForegroundService.
        if (!promote(EngineNotices.running(this, EngineLoad(emptyList(), null)))) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch()
        // After a kill the rooms are gone with the process; nothing is left to keep alive.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun watch() {
        if (watching) return
        watching = true
        val graph = applicationContext.graph
        scope.launch {
            combine(graph.rooms.states, graph.limiter.work, graph.computer.state) { states, work, computer ->
                val lines = EngineLoad.running(states).sorted().map { id ->
                    val name = graph.agents.find(id)?.displayName ?: EngineNotices.defaultName(id)
                    EngineLoad.Line(name, working = work[id]?.busy?.isNotEmpty() == true, starting = states[id] is RoomState.Starting)
                }
                EngineLoad(lines, EngineLoad.computerWork(computer))
            }.distinctUntilChanged().collectLatest { load ->
                if (load.idle) {
                    releaseWakeLock()
                    // A room between stop and start (a restart), or set-up about to begin, should not take the service down with it.
                    delay(EMPTY_GRACE_MS)
                    stopSelf()
                    return@collectLatest
                }
                promote(EngineNotices.running(this@EngineService, load))
                if (load.working) keepAwake() else releaseWakeLock()
            }
        }
    }

    /** "Stop everything": the rooms stop cleanly (sessions are kept), then the service goes. */
    private fun stopEverything() {
        val graph = applicationContext.graph
        graph.scope.launch {
            runCatching { graph.sync.requestSync("Stopped from the notification") }
            runCatching { graph.rooms.stopAll() }
            stop(applicationContext)
        }
    }

    /**
     * Holds the CPU while an agent works, refreshed every few minutes and never for longer than
     * [WAKE_MAX_MS] in one stretch. Runs inside collectLatest, so any change of state ends it.
     */
    private suspend fun keepAwake() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        var held = 0L
        while (held < WAKE_MAX_MS) {
            lock.acquire(WAKE_STEP_MS + WAKE_OVERLAP_MS)
            delay(WAKE_STEP_MS)
            held += WAKE_STEP_MS
        }
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    private fun promote(notification: android.app.Notification): Boolean = try {
        // ServiceCompat drops the special-use type below Android 14, where it does not exist.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, EngineNotices.RUNNING_ID, notification, type)
        true
    } catch (_: RuntimeException) {
        // Not allowed from the background right now (ForegroundServiceStartNotAllowedException).
        false
    }

    companion object {
        internal const val ACTION_STOP_ALL = "com.pocketide.action.STOP_ALL"
        private const val WAKE_TAG = "PocketIDE:agent-working"
        private const val WAKE_STEP_MS = 5 * 60_000L
        private const val WAKE_OVERLAP_MS = 60_000L
        private const val WAKE_MAX_MS = 2 * 60 * 60_000L
        private const val EMPTY_GRACE_MS = 15_000L

        /**
         * Starts the engine's foreground service. Call it when a room starts, or right before the
         * computer is set up, reset or repaired, ideally from the tap that asked for it: Android
         * 12+ refuses a start from the background (false then).
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

        /** Stops the service; it also stops by itself once no room runs. */
        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
