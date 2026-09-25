package com.pocketide.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.limiter.Condition
import com.pocketide.model.AgentInfo
import com.pocketide.model.PhoneSnapshot
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomState
import com.pocketide.sync.DataUsage
import com.pocketide.sync.PhoneSpace
import com.pocketide.sync.SessionBackup
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.manage.BackgroundLimitNote
import com.pocketide.ui.manage.PhoneSpaceNotice
import com.pocketide.ui.manage.StopBanner
import com.pocketide.ui.manage.Told
import com.pocketide.ui.manage.WorkText
import com.pocketide.ui.manage.resumeRooms
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.shell.External
import com.pocketide.ui.screens.onboarding.RestorePlanPanel
import com.pocketide.ui.screens.onboarding.SetUpComputerCard
import com.pocketide.ui.screens.onboarding.SetUpOffer
import com.pocketide.ui.screens.onboarding.rememberRestoreOffer
import com.pocketide.ui.screens.project.AgentMark
import com.pocketide.ui.screens.project.ConfirmDialog
import com.pocketide.ui.screens.project.EmptyState
import com.pocketide.ui.screens.project.NewSessionDialog
import com.pocketide.ui.screens.project.SectionLabel
import com.pocketide.ui.screens.project.WorkFormat
import com.pocketide.ui.screens.project.act
import com.pocketide.ui.screens.project.rememberGraph
import com.pocketide.ui.screens.project.rememberTicker
import kotlinx.coroutines.launch

/**
 * Home: banners that need the owner, the phone strip, projects (New / Import) and the agents
 * with their live state.
 */
@Composable
fun HomeScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val phone by graph.phone.snapshot.collectAsStateWithLifecycle()
    val usage by graph.sync.usage.collectAsStateWithLifecycle()
    val sync by graph.sync.status.collectAsStateWithLifecycle()
    val computer by graph.computer.state.collectAsStateWithLifecycle()
    val conditions by graph.limiter.conditions.collectAsStateWithLifecycle()
    val lastStop by graph.limiter.lastStop.collectAsStateWithLifecycle()
    val work by graph.limiter.work.collectAsStateWithLifecycle()
    val backgroundLimit by graph.sync.backgroundLimit.collectAsStateWithLifecycle()
    val storage by graph.sync.storage.collectAsStateWithLifecycle()
    val now by rememberTicker(graph.clock::now)
    val restorePending by rememberRestoreOffer().pending.collectAsStateWithLifecycle()
    var restorePlanOpen by rememberSaveable { mutableStateOf(false) }

    var newProject by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var agentSheet by remember { mutableStateOf<AgentInfo?>(null) }
    var removing by remember { mutableStateOf<Project?>(null) }
    val context = LocalContext.current

    // The shell draws the title bar (with [HomeBarActions]) and the access banner.
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lastStop?.let { stop ->
                item(key = "stop") {
                    StopBanner(
                        text = stop.message,
                        onResume = {
                            graph.limiter.dismissStop()
                            if (!resumeRooms(graph, stop.agentIds, nav)) {
                                scope.launch { snackbar.showSnackbar("No open session to go back to. Open a project to start one.") }
                            }
                        },
                        onDismiss = graph.limiter::dismissStop,
                    )
                }
            }
            items(conditions.distinctBy { it.id }, key = { "condition:${it.id}" }) { condition ->
                ConditionBanner(condition) {
                    // The phone maker's own page first (it also marks the one-time battery step done).
                    val opened = runCatching { graph.limiter.openFix(context, condition.id) }.getOrDefault(false)
                    val action = condition.fixIntentAction
                    if (!opened && action != null) {
                        External.leaving(context)
                        openSettings(context, action)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                    } else if (!opened) {
                        scope.launch { snackbar.showSnackbar("This phone has no settings page for that.") }
                    }
                }
            }
            backgroundLimit?.let { text -> item(key = "background") { BackgroundLimitNote(text) } }
            syncBanner(sync)?.let { (text, tone) ->
                item(key = "sync") {
                    Banner(Icons.Filled.CloudUpload, text, tone) {
                        TextButton(onClick = nav::waitingUploads) { Text("See what's waiting") }
                    }
                }
            }
            if (storage.phone != PhoneSpace.OK) {
                item(key = "phone-space") {
                    PhoneSpaceNotice(
                        storage,
                        clean = graph.sync::cleanNow,
                        onRaise = nav::settings,
                        onLargest = nav::yourData,
                        say = { text -> scope.launch { snackbar.showSnackbar(text) } },
                    )
                }
            }
            if (restorePlanOpen) {
                item(key = "restore-plan") {
                    Column {
                        RestorePlanPanel()
                        TextButton(onClick = { restorePlanOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("Hide") }
                    }
                }
            } else if (restorePending) {
                item(key = "restore-offer") {
                    Banner(Icons.Filled.CloudDownload, "Your chats, memory and Secrets from before are still in your Drive.", Tone.NEUTRAL) {
                        TextButton(onClick = { restorePlanOpen = true }) { Text("Bring your chats back") }
                    }
                }
            }
            if (SetUpOffer.shows(computer)) item(key = "computer-setup") { SetUpComputerCard() }
            item(key = "phone") { PhoneStrip(phone, usage, onOpen = nav::computer) }

            item(key = "projects-label") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Projects", Modifier.weight(1f))
                    TextButton(onClick = { importing = true }) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import")
                    }
                    FilledTonalButton(onClick = { newProject = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New")
                    }
                }
            }
            if (projects.isEmpty()) {
                item(key = "projects-empty") {
                    EmptyState(
                        Icons.Filled.FolderOpen,
                        "No projects yet",
                        "A project is one private GitHub repository: the code lives on GitHub, your chats stay in your Drive.\n" +
                            "Tap New to start one, or Import to bring a repository you already have.",
                    )
                }
            } else {
                items(projects.sortedByDescending { it.lastActivityAt }, key = { "project:${it.id}" }) { project ->
                    ProjectCard(
                        project = project,
                        sessions = sessions,
                        rooms = rooms,
                        onOpen = { nav.project(project.id) },
                        onRemove = { removing = project },
                    )
                }
            }

            item(key = "agents-label") { SectionLabel("Agents") }
            items(agents, key = { "agent:${it.id}" }) { agent ->
                AgentCard(agent, rooms[agent.id], WorkText.chips(agent.displayName, work[agent.id], now), onUsage = nav::openExternal) { agentSheet = agent }
            }
            item(key = "more-agents") {
                TextButton(onClick = nav::moreAgents) {
                    Text("More agents")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }

    if (newProject) {
        NewProjectDialog(onDismiss = { newProject = false }, onCreated = { id -> newProject = false; nav.project(id) })
    }
    if (importing) {
        ImportSheet(
            addedIds = projects.map { it.id }.toSet(),
            onDismiss = { importing = false },
            onImported = { id -> importing = false; nav.project(id) },
            onAddRepositories = nav::openExternal,
        )
    }
    agentSheet?.let { agent ->
        AgentStart(
            agent = agent,
            projects = projects,
            sessions = sessions,
            onDismiss = { agentSheet = null },
            onOpenSession = { id -> agentSheet = null; nav.agent(id) },
        )
    }
    removing?.let { project ->
        ConfirmDialog(
            title = "Remove ${project.repo} from PocketIDE?",
            text = "Its copy on this phone is removed. The GitHub repository and its branches are not deleted, and you can import it again.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { scope.act(snackbar, "Could not remove", done = "Removed from PocketIDE.") { graph.projects.remove(project.id) } },
            onDismiss = { removing = null },
        )
    }
}

private fun syncBanner(status: SyncStatus): Pair<String, Tone>? = when (status) {
    is SyncStatus.Waiting -> "New chats are waiting safely on the phone: ${status.why.trimEnd('.')} (${WorkFormat.bytes(status.pendingBytes)})." to Tone.WARN
    is SyncStatus.Error -> "Backup to your Drive is not working: ${status.why.trimEnd('.')}." to Tone.ERROR
    else -> null
}

/** Opens a condition's fix; returns a message when the phone has no such page. */
private fun openSettings(context: Context, action: String): String? {
    val intent = Intent(action)
    if (needsPackageUri(action)) intent.data = "package:${context.packageName}".toUri()
    External.leaving(context)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            null
        } catch (_: ActivityNotFoundException) {
            "This phone has no settings page for that."
        }
    }
}

/**
 * Home's part of the shell's title bar: the backup dot, and the places the bottom bar does not
 * reach (Chats, Activity, Settings and Help each have their own button already).
 */
@Composable
fun HomeBarActions(nav: PocketNav) {
    val graph = rememberGraph()
    val sync by graph.sync.status.collectAsStateWithLifecycle()
    val backups by graph.sync.backups.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    SyncDot(sync, backups.values, onClick = nav::waitingUploads)
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Computer") }, onClick = { menu = false; nav.computer() })
            DropdownMenuItem(text = { Text("Your data") }, onClick = { menu = false; nav.yourData() })
            DropdownMenuItem(text = { Text("Usage") }, onClick = { menu = false; nav.usage() })
        }
    }
}

/** The backup state at a glance; tapping it shows what is waiting. */
@Composable
private fun SyncDot(status: SyncStatus, backups: Collection<SessionBackup>, onClick: () -> Unit) {
    val (tone, meaning) = syncDot(status, backups)
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.primary else toneColor(tone)
    // The dot is small; the place to tap is a full touch target around it.
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(onClickLabel = "See what's waiting", role = Role.Button, onClick = onClick)
            .semantics { contentDescription = meaning },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, tone: Tone, action: @Composable (() -> Unit)? = null) {
    val color = toneColor(tone)
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (action != null) Box(Modifier.align(Alignment.End)) { action() }
        }
    }
}

@Composable
private fun ConditionBanner(condition: Condition, onFix: () -> Unit) {
    Surface(color = toneColor(Tone.WARN).copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = toneColor(Tone.WARN), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(condition.title, style = MaterialTheme.typography.titleSmall)
            }
            Text(condition.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (condition.fixIntentAction != null || condition.fixLabel != null) {
                Box(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onFix) { Text(condition.fixLabel ?: "Fix") }
                }
            }
        }
    }
}

@Composable
private fun PhoneStrip(phone: PhoneSnapshot, usage: DataUsage, onOpen: () -> Unit) {
    val known = phone.at > 0
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val (heat, heatTone) = heatLabel(phone.thermal)
            val (battery, batteryTone) = batteryLabel(phone.batteryPercent, phone.charging)
            Stat("Memory free", if (known) WorkFormat.bytes(phone.availRamBytes) else "…", if (phone.lowMemory) Tone.WARN else Tone.NEUTRAL)
            Stat("Heat", if (known) heat else "…", if (known) heatTone else Tone.NEUTRAL)
            Stat("Battery", if (known) battery else "…", if (known) batteryTone else Tone.NEUTRAL)
            Stat("Data today", WorkFormat.bytes(usage.todayMeteredBytes), Tone.NEUTRAL)
            Stat("Storage free", if (known) WorkFormat.bytes(phone.storageFreeBytes) else "…", Tone.NEUTRAL)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, tone: Tone) {
    Column(Modifier.widthIn(min = 72.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneColor(tone),
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    sessions: List<SessionRecord>,
    rooms: Map<String, RoomState>,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val mine = sessions.filter { it.projectId == project.id && it.status == SessionStatus.OPEN }
    val running = mine.count { s -> (rooms[s.agentId] as? RoomState.Running)?.sessionId == s.id }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.repo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StatusChip(if (project.isPrivate) "Private" else "Public", if (project.isPrivate) Tone.OK else Tone.WARN)
                }
                Text(
                    "${project.owner} · last activity ${Ist.dateTime(project.lastActivityAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = buildList {
                    add(WorkFormat.count(mine.size, "open session", "open sessions"))
                    if (running > 0) add("$running running")
                }.joinToString(" · ")
                Text(summary, style = MaterialTheme.typography.bodySmall, color = if (running > 0) toneColor(Tone.OK) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Project actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Remove from PocketIDE") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentInfo, room: RoomState?, chips: List<Told>, onUsage: (String) -> Unit, onClick: () -> Unit) {
    val (state, tone) = roomLabel(room)
    val limits = agentLimits(agent.id)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentMark(agent, agent.id)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(agent.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    when {
                        agent.official -> StatusChip("Official", Tone.OK)
                        agent.verifiedPublisher -> StatusChip("Verified publisher", Tone.NEUTRAL)
                    }
                }
                Text(
                    "${agent.publisher} · $state",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else toneColor(tone),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(limits.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                chips.forEach { StatusChip(it.text, it.tone) }
            }
            val usage = limits.usageUrl
            if (usage != null) {
                TextButton(onClick = { onUsage(usage) }) { Text("Usage") }
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Tapping an agent: continue its current session, or start one in a project. */
@Composable
private fun AgentStart(
    agent: AgentInfo,
    projects: List<Project>,
    sessions: List<SessionRecord>,
    onDismiss: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val graph = rememberGraph()
    val active = remember(agent.id, sessions) {
        runCatching { graph.sessions.activeSession(agent.id) }.getOrNull()?.let { id -> sessions.firstOrNull { it.id == id } }
    }
    var starting by remember { mutableStateOf(active == null) }
    if (projects.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(agent.displayName) },
            text = { Text("Add a project first (New or Import). Every session works on its own branch of a project.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }
    if (!starting && active != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(agent.displayName) },
            text = { Text("Continue \"${active.title}\" in ${active.projectId}, or start a new session?") },
            confirmButton = { TextButton(onClick = { onOpenSession(active.id) }) { Text("Continue") } },
            dismissButton = { OutlinedButton(onClick = { starting = true }) { Text("New session") } },
        )
        return
    }
    NewSessionDialog(
        projects = projects.sortedByDescending { it.lastActivityAt },
        agents = listOf(agent),
        initialProjectId = projects.singleOrNull()?.id,
        initialAgentId = agent.id,
        onDismiss = onDismiss,
        onStarted = onOpenSession,
    )
}
