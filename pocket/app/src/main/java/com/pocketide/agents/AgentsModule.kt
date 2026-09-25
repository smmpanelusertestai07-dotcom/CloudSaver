package com.pocketide.agents

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketide.AppGraph
import com.pocketide.R
import com.pocketide.core.AppDirs
import com.pocketide.core.AppJson
import com.pocketide.core.Channels
import com.pocketide.core.Clock
import com.pocketide.core.Http
import com.pocketide.linux.ComputerState
import com.pocketide.linux.GuestRoot
import com.pocketide.linux.LinuxCommand
import com.pocketide.model.AgentCandidate
import com.pocketide.model.Decision
import com.pocketide.model.SessionRecord
import com.pocketide.rooms.RoomLayout
import com.pocketide.rooms.RoomState
import com.pocketide.sync.SessionBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

fun createAgentCatalog(graph: AppGraph): AgentCatalog {
    val env = GraphAgentsEnv(graph)
    val vsx = OpenVsx(env.http, onBytes = env::recordDownload)
    val download = VerifiedDownload(env.downloads, env::allowDownload, env::recordDownload)
    val folder = File(graph.dirs.base, "agents")
    return OpenVsxCatalog(
        env = env,
        vsx = vsx,
        extensions = ExtensionInstaller(env, vsx, download),
        agy = AgyInstaller(env, download),
        doctor = AgentDoctor(env),
        discovery = Discovery(vsx, graph.clock),
        store = AgentStore(File(folder, "agents.json")),
        packages = File(folder, "packages"),
    )
}

/** The agents module's view of the app graph; each module is reached only when used. */
private class GraphAgentsEnv(private val graph: AppGraph) : AgentsEnv {
    override val dirs: AppDirs get() = graph.dirs
    override val clock: Clock get() = graph.clock
    override val scope: CoroutineScope get() = graph.scope
    override val http: OkHttpClient get() = Http.client
    override val downloads: OkHttpClient get() = Http.downloads

    override val onlyOfficial: StateFlow<Boolean> = graph.settings.settings
        .map { it.onlyOfficialAgents }
        .stateIn(graph.scope, SharingStarted.Eagerly, graph.settings.settings.value.onlyOfficialAgents)

    override fun computerProblem(): String? = when (val state = graph.computer.state.value) {
        ComputerState.Ready, is ComputerState.Updating -> null
        ComputerState.NotInstalled -> "Set up the computer first."
        is ComputerState.Installing -> "The computer is still being set up. Try again when it is ready."
        is ComputerState.Broken -> "${state.why} ${state.fix}"
    }

    /**
     * VS Code's own package.json inside code-server says the version it is built on. Since
     * code-server 4.9x its own version carries the same minor and patch ("4.138.0" is VS Code
     * 1.138.0), which stands in when that file cannot be read.
     */
    override fun vscodeVersion(): SemVer? {
        val guest = GuestRoot(dirs.rootfs)
        guest.readText(VSCODE_PACKAGE, MAX_PACKAGE_JSON)?.let(::packageVersion)?.let { return it }
        val codeServer = guest.readText(CODE_SERVER_PACKAGE, MAX_PACKAGE_JSON)?.let(::packageVersion) ?: return null
        return SemVer(1, codeServer.minor, codeServer.patch)
    }

    override suspend fun runInRoom(agentId: String, argv: List<String>, onLine: (String) -> Unit): Int {
        RoomLayout.hostFolders(dirs, agentId).forEach { folder ->
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create the room's ${folder.name} folder.")
        }
        val command = LinuxCommand(argv = argv, binds = RoomLayout.binds(dirs, agentId), workDir = AppDirs.GUEST_HOME)
        return graph.computer.run(command, onLine)
    }

    override fun roomInUse(agentId: String): Boolean = when (graph.rooms.states.value[agentId]) {
        is RoomState.Running, is RoomState.Starting -> true
        else -> false
    }

    override suspend fun configureRoom(agentId: String) = graph.rooms.configure(agentId)

    override suspend fun saveBeforeRemoving(agentId: String): List<String> = AgentRemoval(RemovalPorts(graph)).saveFirst(agentId)

    override suspend fun deleteRoom(agentId: String) = graph.rooms.delete(agentId)

    override fun allowDownload(bytes: Long): Decision = graph.dataBudget.allow(bytes, DATA_KIND, big = true)

    override fun recordDownload(bytes: Long) = graph.dataBudget.record(bytes, DATA_KIND)

    override fun memoryProblem(): String? {
        val total = graph.phone.snapshot.value.totalRamBytes
        return if (total in 1 until MIN_RAM_BYTES) "This phone has less than the 4 GB of memory an agent needs." else null
    }

    override fun announce(candidates: List<AgentCandidate>) = AgentNotices.newAgents(graph.context, candidates)

    private fun packageVersion(text: String): SemVer? = runCatching {
        (AppJson.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.let(SemVer::parse)

    private companion object {
        const val DATA_KIND = "agents"
        const val VSCODE_PACKAGE = "/opt/code-server/lib/vscode/package.json"
        const val CODE_SERVER_PACKAGE = "/opt/code-server/package.json"
        const val MAX_PACKAGE_JSON = 1024L * 1024

        /** A "4 GB" phone reports about 3.6 GB to apps; a "3 GB" one well under 3 GB. */
        const val MIN_RAM_BYTES = 3_400_000_000L
    }
}

/** Saving a room's sessions before it is removed, through the rooms, sessions and sync modules. */
private class RemovalPorts(private val graph: AppGraph) : AgentRemoval.Ports {
    override suspend fun stopRoom(agentId: String) = graph.rooms.stop(agentId)

    override fun sessions(): List<SessionRecord> = graph.sessions.all.value

    override suspend fun saveNow(sessionId: String): String? = graph.sessions.saveNow(sessionId)

    override suspend fun upload(sessionIds: List<String>) = graph.sync.uploadNow(sessionIds)

    override fun backup(sessionId: String): SessionBackup? = graph.sync.backups.value[sessionId]
}

/** "New agents on Open VSX", on the agents channel. Names come from the registry, so they are shown as plain, short text. */
internal object AgentNotices {
    private const val NOTIFICATION_ID = 4400
    private const val MAX_NAME = 60

    fun newAgents(context: Context, candidates: List<AgentCandidate>) {
        if (candidates.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val title = if (candidates.size == 1) "New agent: ${plain(candidates.single().displayName)}" else "${candidates.size} new agents found"
        val text = "From a verified publisher on Open VSX. Nothing is installed until you add it under More agents."
        val notification = NotificationCompat.Builder(context, Channels.AGENTS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (denied: SecurityException) {
            // Notifications were turned off in the meantime; More agents still lists them.
        }
    }

    fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun plain(text: String): String =
        text.map { if (it.isISOControl()) ' ' else it }.joinToString("").split(Regex("\\s+")).joinToString(" ").trim().take(MAX_NAME)
}
