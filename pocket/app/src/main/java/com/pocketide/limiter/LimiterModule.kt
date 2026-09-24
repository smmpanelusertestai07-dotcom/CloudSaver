package com.pocketide.limiter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.pocketide.AppGraph
import com.pocketide.model.AgentSurface
import com.pocketide.rooms.RoomState

fun createPhoneMonitor(graph: AppGraph): PhoneMonitor = AndroidPhoneMonitor(
    context = graph.context,
    dirs = graph.dirs,
    scope = graph.scope,
    clock = graph.clock,
    roomsActive = {
        runCatching { graph.rooms.states.value.values.any { it is RoomState.Running || it is RoomState.Starting } }.getOrDefault(false)
    },
)

fun createLimiter(graph: AppGraph): Limiter = LimiterImpl(
    phone = graph.phone,
    settings = graph.settings,
    rooms = { graph.rooms },
    sync = { reason -> graph.sync.requestSync(reason) },
    agents = CatalogKinds(graph),
    host = AndroidLimiterHost(graph),
    scope = graph.scope,
    clock = graph.clock,
).also { it.start() }

/** Room kinds and names from the agent catalog; Antigravity is the hub even before the catalog loads. */
private class CatalogKinds(private val graph: AppGraph) : AgentKinds {
    override fun kind(agentId: String): RoomKind {
        val surface = runCatching { graph.agents.find(agentId)?.surface }.getOrNull()
        return when {
            surface == AgentSurface.NATIVE_HUB -> RoomKind.HUB
            surface == null && agentId == "antigravity" -> RoomKind.HUB
            else -> RoomKind.CODE_SERVER
        }
    }

    override fun name(agentId: String): String =
        runCatching { graph.agents.find(agentId)?.displayName }.getOrNull() ?: EngineNotices.defaultName(agentId)
}

private class AndroidLimiterHost(private val graph: AppGraph) : LimiterHost {
    private val context: Context = graph.context
    private val record = EngineRecord.of(context)

    override val vendor: Vendor = ConditionRules.vendor(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

    override fun phoneFacts(): PhoneFacts {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        return PhoneFacts(
            abis = Build.SUPPORTED_ABIS.toList(),
            sdk = Build.VERSION.SDK_INT,
            totalRamBytes = memory.totalMem,
            playServices = playServices(),
        )
    }

    override fun roomsRunning(agentIds: Set<String>) {
        if (agentIds.isEmpty()) {
            record.stoppedCleanly()
        } else {
            record.running(agentIds)
            EngineService.start(context)
        }
    }

    override fun lastExit(): RoomStop? = record.lastStop()

    override fun notifyStopped(stop: RoomStop) = EngineNotices.stopped(context, stop)

    override fun openFix(context: Context, conditionId: String): Boolean {
        // A trip to Settings the app started itself: coming back should not ask for the lock again.
        runCatching { graph.appLock.leavingOnErrand() }
        return OemPages(context, vendor).open(conditionId)
    }

    private fun playServices(): PlayServices = when (
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }.getOrDefault(ConnectionResult.SERVICE_MISSING)
    ) {
        ConnectionResult.SUCCESS -> PlayServices.OK
        ConnectionResult.SERVICE_DISABLED -> PlayServices.DISABLED
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING -> PlayServices.NEEDS_UPDATE
        else -> PlayServices.MISSING
    }
}
