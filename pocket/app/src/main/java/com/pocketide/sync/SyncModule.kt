package com.pocketide.sync

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.pocketide.AppGraph
import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.Device
import com.pocketide.core.SettingsStore
import com.pocketide.google.DriveAuthResult
import com.pocketide.google.DriveStore
import com.pocketide.linux.ComputerState
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.rooms.RoomState
import com.pocketide.vault.KeyState
import com.pocketide.vault.RekeyReason
import com.pocketide.vault.VaultCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

fun createSyncEngine(graph: AppGraph): SyncEngine {
    val engine = DriveSyncEngine(GraphPorts(graph))
    graph.scope.launch(Dispatchers.IO) { engine.warmUp() }
    graph.scope.launch { syncWhileAgentsRun(graph, engine) }
    return engine
}

/**
 * While any agent runs, new transcript bytes are batched into a sync every few minutes (never per
 * keystroke); the end of a task asks for one on its own. The rooms run in the engine's foreground
 * service, so this process is alive for as long as it matters.
 */
private suspend fun syncWhileAgentsRun(graph: AppGraph, engine: SyncEngine) {
    graph.rooms.states
        .map { states -> states.values.any { it !is RoomState.Stopped && it !is RoomState.Failed } }
        .distinctUntilChanged()
        .collectLatest { running ->
            while (running) {
                delay(AGENT_SYNC_EVERY_MS)
                engine.requestSync("agents running")
            }
        }
}

private const val AGENT_SYNC_EVERY_MS = 5 * Durations.MINUTE

fun createDataBudget(graph: AppGraph): DataBudget = meteredBudget(graph)

private fun meteredBudget(graph: AppGraph) = MeteredDataBudget(
    settings = { graph.settings.settings.value },
    network = AndroidNetworkProbe(graph.context),
    counters = PrefsCounterStore(graph.context.getSharedPreferences("pocketide.databudget", Context.MODE_PRIVATE)),
    clock = graph.clock,
)

/** Android's own view of the active network. */
internal class AndroidNetworkProbe(context: Context) : NetworkProbe {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun online(): Boolean {
        val manager = connectivity ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Unknown counts as metered, so a doubtful network never spends the owner's data freely. */
    override fun metered(): Boolean = connectivity?.isActiveNetworkMetered ?: true

    override fun dataSaverRestricted(): Boolean =
        connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}

/** The sync engine's view of the app, through each module's contract only. */
private class GraphPorts(private val graph: AppGraph) : SyncPorts {
    override val dirs: AppDirs get() = graph.dirs
    override val clock: Clock get() = graph.clock
    override val device: DeviceIdentity by lazy { DeviceIdentity(Device.id(graph.context), Device.name()) }
    override val settings: SettingsStore get() = graph.settings
    override val network: NetworkProbe = AndroidNetworkProbe(graph.context)
    override val budget: MeteredDataBudget by lazy { graph.dataBudget as? MeteredDataBudget ?: meteredBudget(graph) }
    override val notifier: SyncNotifier = AndroidSyncNotifier(graph.context)
    override val scheduler: SyncScheduling = WorkScheduler(graph.context)

    override fun drive(): DriveStore = graph.drive

    override fun drive(email: String): DriveStore = graph.drive.withAccount(email)

    override fun account(): String? = graph.driveAuth.email.value

    override fun cipher(): VaultCipher? {
        val state = graph.vault.state.value
        if (state !is KeyState.Ready && state !is KeyState.OnlyOnPhone) return null
        return try {
            graph.vault.cipher()
        } catch (_: IllegalStateException) {
            null
        }
    }

    override fun keyGeneration(): Int = graph.vault.generation()

    override fun localSessions(): List<SessionRecord> = graph.sessions.all.value

    override fun localProjects(): List<Project> = graph.projects.all.value

    override fun activeSessionIds(): Set<String> {
        val agents = graph.agents.installed.value.map { it.id } + graph.rooms.states.value.keys
        return agents.distinct().mapNotNull { graph.sessions.activeSession(it) }.toSet()
    }

    override fun roomsRunning(): Boolean = graph.rooms.states.value.values.any(::isRunning)

    override fun roomRunning(agentId: String): Boolean = graph.rooms.states.value[agentId]?.let(::isRunning) == true

    private fun isRunning(state: RoomState) = state !is RoomState.Stopped && state !is RoomState.Failed

    override suspend fun stopRooms() = graph.rooms.stopAll()

    override suspend fun deleteSessionLocally(sessionId: String) = graph.sessions.delete(sessionId)

    override suspend fun adoptSessions(records: List<SessionRecord>) = graph.sessions.adopt(records)

    override suspend fun sessionsErased(sessionIds: List<String>) = graph.sessions.erased(sessionIds)

    override suspend fun adoptProjects(projects: List<Project>) = graph.projects.adopt(projects)

    override fun backgroundLimit(): String? {
        val context = graph.context
        if (context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true) return Plain.BACKGROUND_OFF
        val bucket = context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && bucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED) Plain.BACKGROUND_RESTRICTED else null
    }

    override suspend fun exportSecrets(): ByteArray? = try {
        graph.secrets.exportBlob()
    } catch (_: IllegalStateException) {
        null
    }

    override suspend fun importSecrets(bytes: ByteArray) = graph.secrets.importBlob(bytes)

    override suspend fun mergeSecrets(bytes: ByteArray) = graph.secrets.mergeBlob(bytes)

    override fun phone(): PhoneSnapshot = graph.phone.snapshot.value

    override fun computerIdle(): Boolean = when (graph.computer.state.value) {
        is ComputerState.Installing, is ComputerState.Updating -> false
        else -> true
    }

    override suspend fun removeComputer() = graph.computer.remove()

    override suspend fun authorizeNewAccount(): DriveAuthResult = graph.driveAuth.authorizeNewAccount()

    override suspend fun rekeyForMove() = graph.vault.rekey(RekeyReason.MOVED_ACCOUNT)

    override suspend fun checkKeyring() {
        graph.vault.checkKeyring()
    }

    /** The secure store's folder (see AppGraph): tokens, the vault key and Secrets. */
    override fun wipeSecureStore() {
        deleteTree(File(graph.context.filesDir, "secure"))
    }

    override suspend fun forgetVaultKey() = graph.vault.forget()
}
