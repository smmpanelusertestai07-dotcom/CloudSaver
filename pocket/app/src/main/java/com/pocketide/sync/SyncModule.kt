package com.pocketide.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.launch
import java.io.File

fun createSyncEngine(graph: AppGraph): SyncEngine {
    val engine = DriveSyncEngine(GraphPorts(graph))
    graph.scope.launch(Dispatchers.IO) { engine.warmUp() }
    return engine
}

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

    override fun roomsRunning(): Boolean =
        graph.rooms.states.value.values.any { it !is RoomState.Stopped && it !is RoomState.Failed }

    override suspend fun stopRooms() = graph.rooms.stopAll()

    override suspend fun deleteSessionLocally(sessionId: String) = graph.sessions.delete(sessionId)

    override suspend fun exportSecrets(): ByteArray? = try {
        graph.secrets.exportBlob()
    } catch (_: IllegalStateException) {
        null
    }

    override suspend fun importSecrets(bytes: ByteArray) = graph.secrets.importBlob(bytes)

    override fun phone(): PhoneSnapshot = graph.phone.snapshot.value

    override fun computerIdle(): Boolean = when (graph.computer.state.value) {
        is ComputerState.Installing, is ComputerState.Updating -> false
        else -> true
    }

    override suspend fun authorizeNewAccount(): DriveAuthResult = graph.driveAuth.authorizeNewAccount()

    override suspend fun rekeyForMove() = graph.vault.rekey(RekeyReason.MOVED_ACCOUNT)

    /** The secure store's folder (see AppGraph): tokens, the vault key and Secrets. */
    override fun wipeSecureStore() {
        deleteTree(File(graph.context.filesDir, "secure"))
    }
}
