package com.pocketide.rooms

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.AppGraph
import com.pocketide.R
import com.pocketide.core.AppDirs
import com.pocketide.core.Channels
import com.pocketide.limiter.EngineService
import com.pocketide.model.Project
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

fun createRooms(graph: AppGraph): Rooms = RoomManager(GraphRoomsEnv(graph))

/** Rooms' view of the app graph. Every module is reached when used, never while rooms are created. */
private class GraphRoomsEnv(private val graph: AppGraph) : RoomsEnv {
    override val dirs: AppDirs get() = graph.dirs
    override val scope get() = graph.scope
    override val computer get() = graph.computer
    override val portBridge get() = graph.portBridge
    override val phoneBridge get() = graph.phoneBridge
    override val assets: RoomAssets = AndroidRoomAssets(graph.context.assets)
    private val notices = RoomNotices(graph.context, graph.clock::now)

    @Volatile
    private var identity: Map<String, String>? = null

    override fun now() = graph.clock.now()
    override fun agentInfo(agentId: String) = graph.agents.find(agentId)
    override fun agents() = graph.agents.installed.value.map { it.id }
    override fun sessions() = graph.sessions.all.value
    override fun activeSession(agentId: String) = graph.sessions.activeSession(agentId)
    override fun project(projectId: String) = graph.projects.all.value.firstOrNull { it.id == projectId }
    override suspend fun variables(projectId: String, agentId: String) = graph.secrets.variablesFor(projectId, agentId)
    override fun trust(projectId: String) = graph.projects.trustOf(projectId)
    override fun canStartAgent(agentId: String) = graph.limiter.canStartAgent(agentId)
    override suspend fun makeRoomFor(agentId: String) = graph.limiter.makeRoomFor(agentId)
    override fun setBusy(agentId: String, what: String, busy: Boolean) = graph.limiter.setBusy(agentId, what, busy)
    override fun used(agentId: String) = graph.limiter.touch(agentId)
    override fun keepEngineAlive() = EngineService.start(graph.context)
    override fun idleSleepMinutes() = graph.settings.settings.value.idleSleepMinutes
    override fun canStartHeavyWork(what: String) = graph.limiter.canStartHeavyWork(what)
    override fun allowDownload(bytes: Long, kind: String) = graph.dataBudget.allow(bytes, kind, big = true)
    override fun recordDownload(bytes: Long, kind: String) = graph.dataBudget.record(bytes, kind)
    override suspend fun ensureInstalled(agentId: String) = graph.agents.ensureInstalled(agentId)

    override fun fontSize(): Int =
        (BASE_FONT_SIZE * graph.context.resources.configuration.fontScale).roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)

    /** 2.6.0's rule: 512 MB of heap on a 4 GB phone, more where there is more. */
    override fun heapMegabytes(): Int {
        val memory = ActivityManager.MemoryInfo()
        graph.context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val gigabytes = memory.totalMem / 1_000_000_000.0
        return when {
            gigabytes >= 11.5 -> 1536
            gigabytes >= 7.5 -> 1024
            gigabytes >= 5.5 -> 768
            else -> 512
        }
    }

    override suspend fun gitIdentity(): Map<String, String> {
        identity?.let { return it }
        val account = withTimeoutOrNull(IDENTITY_TIMEOUT_MS) { runCatching { graph.gitHub.me() }.getOrNull() } ?: return emptyMap()
        val name = account.name?.takeIf { it.isNotBlank() } ?: account.login
        val email = "${account.id}+${account.login}@users.noreply.github.com"
        return mapOf(
            "GIT_AUTHOR_NAME" to name,
            "GIT_AUTHOR_EMAIL" to email,
            "GIT_COMMITTER_NAME" to name,
            "GIT_COMMITTER_EMAIL" to email,
        ).also { identity = it }
    }

    override fun notify(agentId: String, sessionId: String?, title: String, text: String) = notices.post(agentId, sessionId, title, text)

    override fun phone() = graph.phone.snapshot.value
    override fun guard() = graph.limiter.guard.value
    override fun maxAgents() = graph.limiter.maxAgents()
    override fun ownerPresent() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    override suspend fun autosave(sessionId: String) = graph.sessions.autosave(sessionId)
    override suspend fun putOnMain(sessionId: String) = graph.sessions.putOnMain(sessionId)
    override fun templates() = graph.builds.templates()
    override suspend fun runBuild(projectId: String, templateId: String, ref: String) = graph.builds.run(projectId, templateId, ref)
    override suspend fun buildProgress(projectId: String, runId: Long) = graph.builds.progress(projectId, runId)
    override suspend fun collect(projectId: String, sessionId: String, runId: Long) = graph.builds.collect(projectId, sessionId, runId)
    override suspend fun openPullRequest(project: Project, head: String, title: String, body: String) =
        graph.gitHub.openPullRequest(project.owner, project.repo, head, project.defaultBranch, title, body)
    override suspend fun addMedia(sessionId: String, file: File, name: String) = graph.media.add(sessionId, file, name, "agent")

    private companion object {
        const val BASE_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 24
        const val IDENTITY_TIMEOUT_MS = 5_000L
    }
}

/**
 * "Agent needs you" and "Agent finished" notifications. The text comes from inside Linux, so it
 * is untrusted: shown as plain text, one line, capped, at most one per agent every half minute,
 * silent at night, and not at all while the owner has PocketIDE on the screen.
 */
internal class RoomNotices(private val context: Context, private val now: () -> Long) {
    private val lastPosted = ConcurrentHashMap<String, Long>()

    fun post(agentId: String, sessionId: String?, title: String, text: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val at = now()
        val previous = lastPosted[agentId]
        if (previous != null && at - previous < MIN_GAP_MS) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        lastPosted[agentId] = at
        val notification = NotificationCompat.Builder(context, Channels.AGENTS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(plain(title, MAX_TITLE))
            .setContentText(plain(text, MAX_TEXT).ifEmpty { "Open PocketIDE to see." })
            .setAutoCancel(true)
            .setSilent(isNight())
            .setContentIntent(openSession(agentId, sessionId))
            .build()
        try {
            manager.notify(agentId, NOTIFICATION_ID, notification)
        } catch (denied: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }

    private fun openSession(agentId: String, sessionId: String?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (sessionId != null) intent.putExtra(EXTRA_SESSION, sessionId)
        return PendingIntent.getActivity(context, agentId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().apply { timeInMillis = now() }.get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_FROM || hour < NIGHT_UNTIL
    }

    private fun plain(text: String, limit: Int): String =
        text.map { if (it.isISOControl()) ' ' else it }.joinToString("").split(Regex("\\s+")).joinToString(" ").trim().take(limit)

    companion object {
        /** The session a notification opens (an extra on the app's launch intent). */
        const val EXTRA_SESSION = "com.pocketide.extra.SESSION"
        private const val NOTIFICATION_ID = 4300
        private const val MIN_GAP_MS = 30_000L
        private const val MAX_TITLE = 60
        private const val MAX_TEXT = 200
        private const val NIGHT_FROM = 22
        private const val NIGHT_UNTIL = 7
    }
}
