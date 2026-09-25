package com.pocketide.ui.screens.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pocketide.AppGraph
import com.pocketide.core.Ist
import com.pocketide.github.WorkflowRun
import com.pocketide.limiter.RoomWork
import com.pocketide.model.AgentInfo
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.rooms.RoomState
import com.pocketide.schedule.ScheduledTask
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManageList
import com.pocketide.ui.manage.ManageText
import com.pocketide.ui.manage.ScheduleForm
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.SessionUsage
import com.pocketide.ui.manage.ToneLine
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.UsageMeter
import com.pocketide.ui.manage.BackgroundLimitNote
import com.pocketide.ui.manage.StopBanner
import com.pocketide.ui.manage.WorkText
import com.pocketide.ui.manage.attempt
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.manage.resumeRooms
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.project.rememberTicker
import com.pocketide.ui.shell.Formats
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** A build on GitHub Actions that has not finished, with its project. */
private data class LiveBuild(val project: Project, val run: WorkflowRun)

/** The last answer from GitHub; [reachable] is false when no project could be asked. */
private data class LiveBuilds(val runs: List<LiveBuild>, val reachable: Boolean)

private const val BUILD_POLL_MS = 30_000L
private const val RECENT_PROJECTS = 3
private const val STOP_ALL = "stop-all"

/**
 * What is running now: agents' rooms (with Stop), sync, builds on GitHub, scheduled tasks,
 * the phone's guard in plain words, and mobile data used. The shell draws the title bar.
 */
@Composable
fun ActivityScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val runner = rememberActionRunner()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val guard by graph.limiter.guard.collectAsStateWithLifecycle()
    val conditions by graph.limiter.conditions.collectAsStateWithLifecycle()
    val sync by graph.sync.status.collectAsStateWithLifecycle()
    val waiting by graph.sync.waiting.collectAsStateWithLifecycle()
    val usage by graph.sync.usage.collectAsStateWithLifecycle()
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    val tasks by graph.schedules.tasks.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val builds = rememberLiveBuilds(graph, projects)
    val lastStop by graph.limiter.lastStop.collectAsStateWithLifecycle()
    val stops by graph.rooms.stops.collectAsStateWithLifecycle()
    val work by graph.limiter.work.collectAsStateWithLifecycle()
    val backgroundLimit by graph.sync.backgroundLimit.collectAsStateWithLifecycle()
    val now by rememberTicker(graph.clock::now)
    // Rooms that are running again need no banner about their last stop.
    val stopped = WorkText.newsworthy(stops).filterKeys { rooms[it] !is RoomState.Running && rooms[it] !is RoomState.Starting }

    Box(Modifier.fillMaxSize()) {
        ManageList {
            lastStop?.let { stop ->
                item {
                    StopBanner(
                        text = stop.message,
                        onResume = {
                            graph.limiter.dismissStop()
                            if (!resumeRooms(graph, stop.agentIds, nav)) runner.say("No open session to go back to. Open a project to start one.")
                        },
                        onDismiss = graph.limiter::dismissStop,
                    )
                }
            }
            stopped.forEach { (agentId, stop) ->
                // The limiter's banner already says it for the rooms it stopped.
                if (lastStop?.agentIds?.contains(agentId) == true) return@forEach
                val name = agents.firstOrNull { it.id == agentId }?.displayName ?: agentId
                item {
                    StopBanner(
                        text = "$name: ${stop.message}",
                        onResume = {
                            if (!resumeRooms(graph, listOf(agentId), nav)) runner.say("No open session to go back to. Open a project to start one.")
                        },
                        onDismiss = null,
                    )
                }
            }
            item { SectionLabel("This phone") }
            item {
                SectionCard(null) {
                    ToneLine(ManageText.guard(guard))
                    conditions.forEach { Hint("${it.title}: ${it.explanation}") }
                    PhoneLines(snapshot)
                }
            }
            item { SectionLabel("Agents") }
            item { AgentsCard(graph, agents, rooms, sessions, work, now, runner, nav) }
            item { SectionLabel("Sessions, time and tokens") }
            item { UsageCard(sessions, agents, now) }
            item { SectionLabel("Sync with Google Drive") }
            item {
                SectionCard(null) {
                    ToneLine(ManageText.sync(sync, Ist::dateTime, ManageFormat::bytes))
                    backgroundLimit?.let { BackgroundLimitNote(it) }
                    if (waiting.isNotEmpty()) {
                        val bytes = waiting.sumOf { it.bytes }
                        TextButton(onClick = nav::waitingUploads) {
                            Text("${ManageFormat.count(waiting.size, "chat")} waiting to upload · ${ManageFormat.bytes(bytes)}")
                        }
                    }
                }
            }
            item { SectionLabel("Builds on GitHub") }
            item { BuildsCard(builds, projects.isEmpty(), nav) }
            item { SectionLabel("Scheduled tasks") }
            item { TasksCard(tasks, agents, graph.clock.now(), nav) }
            item { SectionLabel("Mobile data") }
            item {
                SectionCard(null) {
                    val limitMb = settings.mobileDailyLimitMb
                    InfoRow("Today", ManageFormat.bytes(usage.todayMeteredBytes))
                    if (limitMb > 0) {
                        InfoRow("Daily limit", "${ManageFormat.bytes(usage.todayLimitedBytes)} of $limitMb MB")
                        UsageMeter(usage.todayLimitedBytes.toDouble() / (limitMb * 1_000_000.0))
                    }
                    InfoRow("This month", ManageFormat.bytes(usage.monthMeteredBytes))
                    usage.byType.entries.sortedByDescending { it.value }.filter { it.value > 0 }.take(5).forEach { (type, bytes) ->
                        InfoRow(Formats.dataKind(type), ManageFormat.bytes(bytes))
                    }
                    Hint(
                        if (limitMb > 0) {
                            "Only mobile data counts; Wi-Fi is free. The daily limit is for PocketIDE's own transfers; " +
                                "the agents' own traffic is counted but never blocked. " +
                                if (settings.wifiOnlyBigDownloads) "Big downloads wait for Wi-Fi." else "Big downloads ask first and show their size."
                        } else {
                            "PocketIDE uses no mobile data for its own transfers. The agents' own traffic is counted but never blocked."
                        },
                    )
                }
            }
        }
        SnackbarHost(runner.snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PhoneLines(s: PhoneSnapshot) {
    if (s.totalRamBytes > 0) {
        InfoRow("Memory", "${ManageFormat.bytes(s.totalRamBytes - s.availRamBytes)} used · ${ManageFormat.bytes(s.availRamBytes)} free")
        UsageMeter(1.0 - s.availRamBytes.toDouble() / s.totalRamBytes)
    }
    if (s.at > 0) {
        InfoRow("Battery", "${s.batteryPercent}%" + if (s.charging) " · charging" else "")
        if (s.storageTotalBytes > 0) InfoRow("Storage", "${ManageFormat.bytes(s.storageFreeBytes)} free")
    }
}

@Composable
private fun AgentsCard(
    graph: AppGraph,
    agents: List<AgentInfo>,
    rooms: Map<String, RoomState>,
    sessions: List<SessionRecord>,
    work: Map<String, RoomWork>,
    now: Long,
    runner: ActionRunner,
    nav: PocketNav,
) {
    val active = agents.filter { rooms[it.id].let { state -> state is RoomState.Running || state is RoomState.Starting } }
    SectionCard(null) {
        if (active.isEmpty()) Hint("No agent is running. Open a project to start one.")
        active.forEachIndexed { index, agent ->
            if (index > 0) HorizontalDivider()
            val state = rooms[agent.id] ?: RoomState.Stopped
            val sessionId = remember(agent.id, state) { attemptSync { graph.sessions.activeSession(agent.id) } }
            val session = sessions.firstOrNull { it.id == sessionId }
            val told = ManageText.room(state, ManageFormat::bytes)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = sessionId != null) { sessionId?.let(nav::agent) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(agent.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint(listOfNotNull(session?.title, told.text).joinToString(" · "))
                    WorkText.chips(agent.displayName, work[agent.id], now).forEach { StatusChip(it.text, it.tone) }
                }
                Spacer(Modifier.width(8.dp))
                val key = "stop:${agent.id}"
                FilledTonalButton(onClick = { runner.run(key, done = "${agent.displayName} stopped.") { graph.rooms.stop(agent.id) } }, enabled = !runner.isBusy(key)) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
        val failed = agents.mapNotNull { agent -> (rooms[agent.id] as? RoomState.Failed)?.let { agent to it } }
        failed.forEach { (agent, state) -> ToneLine(Told("${agent.displayName}: ${state.why}", Tone.ERROR)) }
        if (active.isNotEmpty()) {
            HorizontalDivider()
            OutlinedButton(
                onClick = { runner.run(STOP_ALL, done = "Every agent stopped. Chats and files are kept.") { graph.rooms.stopAll() } },
                enabled = !runner.isBusy(STOP_ALL),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop everything") }
        }
    }
}

/** Reads a plain value from a module without letting a module error reach the screen. */
private inline fun <T> attemptSync(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

@Composable
private fun BuildsCard(live: LiveBuilds?, noProjects: Boolean, nav: PocketNav) {
    SectionCard(null) {
        val builds = live?.runs.orEmpty()
        when {
            noProjects -> Hint("No projects yet.")
            live == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            !live.reachable && builds.isEmpty() -> ToneLine(Told("GitHub could not be reached. This is checked again every 30 seconds.", Tone.WARN))
            builds.isEmpty() -> Hint("No builds running in your recent projects.")
            else -> builds.forEachIndexed { index, build ->
                if (index > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { nav.openExternal(build.run.htmlUrl) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(build.run.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint("${build.project.repo} · ${build.run.branch}")
                    }
                    StatusChip(runStatus(build.run.status), Tone.WARN)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open on GitHub")
                }
            }
        }
    }
}

private fun runStatus(status: String): String = when (status) {
    "queued", "requested", "pending", "waiting" -> "Waiting"
    "in_progress" -> "Running"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun UsageCard(sessions: List<SessionRecord>, agents: List<AgentInfo>, now: Long) {
    // Recounted when the ticker moves (about once a minute), not on every recomposition.
    val periods = remember(sessions, now) { SessionUsage.periods(sessions, now, Ist.zone()) }
    SectionCard(null) {
        periods.forEachIndexed { index, period ->
            if (index > 0) HorizontalDivider()
            InfoRow(
                period.period.label,
                if (period.active == 0) "No sessions" else ManageFormat.duration(period.timeMs),
            )
            period.agents.forEach { usage ->
                val name = agents.firstOrNull { it.id == usage.agentId }?.displayName ?: usage.agentId
                Hint("$name: ${SessionUsage.line(usage)}")
            }
        }
        Hint(
            "Time runs from a session's start to its last activity. Tokens are each session's total, " +
                "shown only where the agent records them.",
        )
    }
}

@Composable
private fun TasksCard(tasks: List<ScheduledTask>, agents: List<AgentInfo>, now: Long, nav: PocketNav) {
    val enabled = tasks.filter { it.enabled }
    SectionCard(null) {
        if (enabled.isEmpty()) Hint("No scheduled tasks are on.")
        enabled.sortedBy { ScheduleForm.nextDueAt(it, now) ?: Long.MAX_VALUE }.forEach { task ->
            val due = ScheduleForm.nextDueAt(task, now)
            val agent = agents.firstOrNull { it.id == task.agentId }?.displayName ?: task.agentId
            InfoRow(task.title, if (due == null) "Off" else "Next ${ManageFormat.inFuture(due - now)} · $agent")
        }
        if (enabled.isNotEmpty()) Hint("Tasks run only while the phone is charging on Wi-Fi.")
        TextButton(onClick = { nav.schedules(null) }) { Text("Manage scheduled tasks") }
    }
}

/** Unfinished Actions runs in the most recently used projects, refreshed while the screen is open. */
@Composable
private fun rememberLiveBuilds(graph: AppGraph, projects: List<Project>): LiveBuilds? {
    val recent = remember(projects) { projects.sortedByDescending { it.lastActivityAt }.take(RECENT_PROJECTS) }
    var builds by remember { mutableStateOf<LiveBuilds?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(recent, lifecycle) {
        if (recent.isEmpty()) {
            builds = LiveBuilds(emptyList(), reachable = true)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // A failed poll keeps the builds last seen rather than showing none.
                builds = fetchLiveBuilds(graph, recent)?.let { LiveBuilds(it, reachable = true) }
                    ?: LiveBuilds(builds?.runs.orEmpty(), reachable = false)
                delay(BUILD_POLL_MS)
            }
        }
    }
    return builds
}

private suspend fun fetchLiveBuilds(graph: AppGraph, projects: List<Project>): List<LiveBuild>? = coroutineScope {
    val results = projects.map { project ->
        async { attempt { graph.builds.recentRuns(project.id) }.getOrNull()?.map { LiveBuild(project, it) } }
    }.awaitAll()
    if (results.all { it == null }) null else results.filterNotNull().flatten().filter { it.run.status != "completed" }
}
