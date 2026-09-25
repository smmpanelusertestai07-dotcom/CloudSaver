package com.pocketide.limiter

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Thermal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads the phone every 5 seconds while the app is on screen or a room runs, every 30 seconds
 * otherwise. Heat, network, power and Data Saver changes wake it at once, so the limiter reacts
 * to a hot phone or an unplugged charger without waiting for the next tick.
 */
internal class AndroidPhoneMonitor(
    private val context: Context,
    private val dirs: AppDirs,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** True while any room is starting or running; read on each tick, never at construction. */
    private val roomsActive: () -> Boolean,
) : PhoneMonitor {
    private val flow = MutableStateFlow(PhoneSnapshot.UNKNOWN)
    override val snapshot: StateFlow<PhoneSnapshot> = flow

    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val processes = ProcessReader(uid = Process.myUid(), selfPid = Process.myPid())

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val listening = AtomicBoolean(false)
    private var loop: Job? = null

    private val dataBytes = AtomicLong(0)
    private val dataMeasuredAt = AtomicLong(0)
    private val measuring = AtomicBoolean(false)

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { wake() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()
        override fun onLost(network: Network) = wake()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = wake()
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = wake()
    }
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wake()
    }

    @Synchronized
    override fun start() {
        if (loop?.isActive == true) return
        listen()
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { refresh() }
                withTimeoutOrNull(interval()) { wakeups.receive() }
            }
        }
    }

    @Synchronized
    override fun stop() {
        loop?.cancel()
        loop = null
        unlisten()
    }

    override suspend fun refresh(): PhoneSnapshot = withContext(Dispatchers.IO) {
        val memory = ActivityManager.MemoryInfo().also { info -> activity?.getMemoryInfo(info) }
        val battery = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val storage = runCatching { StatFs(dirs.base.path) }.getOrNull()
        val own = processes.read()
        val network = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        measureDataIfDue()
        PhoneSnapshot(
            at = clock.now(),
            totalRamBytes = memory.totalMem,
            availRamBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            appPssBytes = own.memoryBytes,
            thermal = thermal(),
            batteryPercent = batteryPercent(battery),
            charging = charging(battery),
            powerSave = power?.isPowerSaveMode == true,
            storageFreeBytes = storage?.availableBytes ?: 0,
            storageTotalBytes = storage?.totalBytes ?: 0,
            appDataBytes = dataBytes.get(),
            processCount = own.children,
            online = network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            metered = network != null && !unmetered(network),
            dataSaver = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            standbyBucket = runCatching { usage?.appStandbyBucket }.getOrNull(),
            backgroundRestricted = activity?.isBackgroundRestricted == true,
        ).also { flow.value = it }
    }

    private fun interval(): Long {
        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        return if (visible || roomsActive()) FAST_MS else SLOW_MS
    }

    private fun wake() {
        wakeups.trySend(Unit)
    }

    private fun thermal(): Thermal {
        val status = power?.currentThermalStatus ?: return Thermal.NONE
        return Thermal.entries.getOrElse(status) { Thermal.SHUTDOWN }
    }

    private fun batteryPercent(battery: Intent?): Int {
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) 100 else level * 100 / scale
    }

    /** Plugged in counts as charging: a full battery on the charger is not a battery to protect. */
    private fun charging(battery: Intent?): Boolean {
        if (battery == null) return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun unmetered(network: NetworkCapabilities): Boolean =
        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (Build.VERSION.SDK_INT >= 30 && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))

    /** Walking the whole computer costs seconds, so it runs at most every 10 minutes, apart from the tick. */
    private fun measureDataIfDue() {
        if (clock.now() - dataMeasuredAt.get() < DATA_EVERY_MS && dataMeasuredAt.get() > 0) return
        if (!measuring.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                dataBytes.set(DirectorySize.of(listOf(dirs.base, dirs.cacheBase)))
                dataMeasuredAt.set(clock.now())
                flow.value = flow.value.let { if (it.at > 0) it.copy(appDataBytes = dataBytes.get()) else it }
            } finally {
                measuring.set(false)
            }
        }
    }

    private fun listen() {
        if (!listening.compareAndSet(false, true)) return
        runCatching { power?.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermalListener) }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(context, changes, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    private fun unlisten() {
        if (!listening.compareAndSet(true, false)) return
        runCatching { power?.removeThermalStatusListener(thermalListener) }
        // At most 100 callbacks per app: one left behind per start would eventually throw.
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(changes) }
    }

    private companion object {
        const val FAST_MS = 5_000L
        const val SLOW_MS = 30_000L
        const val DATA_EVERY_MS = 10 * 60_000L
    }
}
