package com.pocketide.ui.screens.project

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.model.AgentInfo
import com.pocketide.model.Project
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.rooms.RoomState
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.web.AgentWebView
import com.pocketide.ui.web.rememberTerminalState
import com.pocketide.ui.web.rememberWebViewHolder
import kotlinx.coroutines.launch

private enum class ProjectTab(val label: String) {
    SESSIONS("Sessions"),
    PREVIEW("Preview"),
    MEDIA("Media"),
    BUILDS("Builds"),
    TERMINAL(">_"),
}

/** One project: its sessions by agent, and the Preview, Media, Builds and terminal tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(projectId: String, nav: PocketNav) {
    val graph = rememberGraph()
    val snackbar = remember { SnackbarHostState() }
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val allSessions by graph.sessions.all.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val project = projects.firstOrNull { it.id == projectId }
    val sessions = remember(allSessions, projectId) {
        allSessions.filter { it.projectId == projectId && it.status != SessionStatus.DELETED }
    }

    var tab by rememberSaveable { mutableStateOf(ProjectTab.SESSIONS) }
    var chosenId by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    val selected = sessions.firstOrNull { it.id == chosenId } ?: defaultSession(sessions)
    var cloneProblem by remember(projectId) { mutableStateOf<String?>(null) }
    var cloneTries by remember(projectId) { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(projectId, cloneTries) {
        cloneProblem = null
        attempt { graph.projects.ensureCloned(projectId) }.onFailure { cloneProblem = plainReason(it) }
    }

    val terminal = rememberTerminalState("term:${selected?.id}")
    val preview = rememberPreviewState(selected?.id ?: "none")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.repo ?: projectId.substringAfter('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            project?.owner ?: projectId.substringBefore('/'),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Variables & Secrets") }, onClick = { menu = false; nav.secrets(projectId) })
                        DropdownMenuItem(text = { Text("Scheduled tasks") }, onClick = { menu = false; nav.schedules(projectId) })
                        DropdownMenuItem(text = { Text("Open on GitHub") }, onClick = { menu = false; nav.openExternal("https://github.com/$projectId") })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == ProjectTab.SESSIONS && project != null) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New session") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (project == null) {
                EmptyState(
                    Icons.Filled.Forum,
                    "Project not on this phone",
                    "It may have been removed from PocketIDE. Its GitHub repository is not affected.",
                )
                return@Column
            }
            ProjectHeader(project, sessions.size, cloneProblem) { cloneTries++ }
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                ProjectTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label, fontFamily = if (t == ProjectTab.TERMINAL) FontFamily.Monospace else null) },
                    )
                }
            }
            if (tab != ProjectTab.SESSIONS && selected != null) {
                SessionPicker(sessions, selected, agents) { chosenId = it }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ProjectTab.SESSIONS -> SessionsTab(projectId, sessions, agents, rooms, nav, snackbar)
                    ProjectTab.BUILDS -> BuildsPanel(projectId, sessions, selected, nav, snackbar)
                    else -> if (selected == null) {
                        EmptyState(
                            Icons.Filled.Forum,
                            "No session yet",
                            "Preview, Media and the terminal belong to a session. Start one in the Sessions tab.",
                        )
                    } else {
                        when (tab) {
                            ProjectTab.PREVIEW -> PreviewPanel(selected.id, preview, nav, snackbar)
                            ProjectTab.MEDIA -> MediaPanel(selected.id, selected.pendingVideos)
                            ProjectTab.TERMINAL -> TerminalPanel(selected.id, terminal, nav, snackbar)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    if (creating && project != null) {
        NewSessionDialog(
            projects = listOf(project),
            agents = agents,
            initialProjectId = project.id,
            initialAgentId = null,
            onDismiss = { creating = false },
            onStarted = { id ->
                creating = false
                nav.agent(id)
            },
        )
    }
}

@Composable
private fun ProjectHeader(project: Project, sessionCount: Int, cloneProblem: String?, onRetryClone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (project.isPrivate) "Private" else "Public", if (project.isPrivate) Tone.OK else Tone.WARN)
            Text(
                "Last activity ${Ist.dateTime(project.lastActivityAt)} · ${WorkFormat.count(sessionCount, "session", "sessions")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cloneProblem != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "The code is not on this phone yet: $cloneProblem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetryClone) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun SessionPicker(sessions: List<SessionRecord>, selected: SessionRecord, agents: List<AgentInfo>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        TextButton(onClick = { open = true }) {
            Text(
                "Session: ${selected.title} · ${agentName(agents.firstOrNull { it.id == selected.agentId }, selected.agentId)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sessions.sortedByDescending { it.lastActivityAt }.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${agentName(agents.firstOrNull { it.id == s.agentId }, s.agentId)} · ${Ist.dateTime(s.lastActivityAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(s.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionsTab(
    projectId: String,
    sessions: List<SessionRecord>,
    agents: List<AgentInfo>,
    rooms: Map<String, RoomState>,
    nav: PocketNav,
    snackbar: SnackbarHostState,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var putting by remember { mutableStateOf<SessionRecord?>(null) }
    var changes by remember { mutableStateOf<SessionRecord?>(null) }
    var deleting by remember { mutableStateOf<SessionRecord?>(null) }
    val groups = remember(sessions, projectId) { sessionsByAgent(sessions, projectId) }

    if (groups.isEmpty()) {
        EmptyState(
            Icons.Filled.Forum,
            "No sessions yet",
            "A session is one chat with an agent, on its own branch of this project. Tap New session to start one.",
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.forEach { (agentId, list) ->
                val agent = agents.firstOrNull { it.id == agentId }
                item(key = "agent:$agentId") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentMark(agent, agentId, size = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(agentName(agent, agentId), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(WorkFormat.count(list.size, "session", "sessions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.id }) { session ->
                    val room = rooms[session.agentId]
                    SessionCard(
                        session = session,
                        running = room is RoomState.Running && room.sessionId == session.id,
                        onOpen = { nav.agent(session.id) },
                        onChanges = { changes = session },
                        onPutOnMain = { putting = session },
                        onDelete = { deleting = session },
                    )
                }
            }
        }
    }

    putting?.let { PutOnMainFlow(it, onClose = { putting = null }, onOpenSession = nav::agent) }
    changes?.let { ChangesSheet(it, onDismiss = { changes = null }) }
    deleting?.let { session ->
        ConfirmDialog(
            title = "Delete \"${session.title}\"?",
            text = DELETE_CHAT_TEXT,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { scope.act(snackbar, "Could not delete", done = "Moved to Recently deleted.") { graph.sessions.delete(session.id) } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionRecord,
    running: Boolean,
    onOpen: () -> Unit,
    onChanges: () -> Unit,
    onPutOnMain: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (label, tone) = sessionStatusLabel(session.status, running)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    StatusChip(label, tone)
                }
                Text(
                    "Started ${Ist.dateTime(session.startedAt)} · last ${Ist.dateTime(session.lastActivityAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${WorkFormat.count(session.commits, "commit", "commits")} · ${WorkFormat.count(session.filesChanged, "file", "files")} changed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(session.branch, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                waitingVideosText(session.pendingVideos)?.let { StatusChip(it, Tone.WARN) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Session actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Changes") }, onClick = { menu = false; onChanges() })
                    if (session.status == SessionStatus.OPEN || session.status == SessionStatus.CONFLICT_COPY) {
                        DropdownMenuItem(text = { Text("Put on main") }, onClick = { menu = false; onPutOnMain() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/**
 * Starts a session: pick the agent (and the project when there is a choice) and an optional
 * title. When the phone cannot take another agent now, the limiter's reason is shown.
 */
@Composable
fun NewSessionDialog(
    projects: List<Project>,
    agents: List<AgentInfo>,
    initialProjectId: String?,
    initialAgentId: String?,
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var projectId by remember { mutableStateOf(initialProjectId ?: projects.firstOrNull()?.id) }
    var agentId by remember { mutableStateOf(initialAgentId ?: agents.firstOrNull()?.id) }
    var title by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val decision = remember(agentId) { agentId?.let { id -> runCatching { graph.limiter.canStartAgent(id) }.getOrNull() } }
    val blocked = decision != null && !decision.allowed

    AlertDialog(
        onDismissRequest = { if (!starting) onDismiss() },
        title = { Text("New session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initialProjectId == null) {
                    SectionLabel("Project")
                    if (projects.isEmpty()) Text("Add a project first.")
                    projects.forEach { p -> ChoiceRow(p.id, projectId == p.id) { projectId = p.id } }
                }
                if (initialAgentId == null) {
                    SectionLabel("Agent")
                    agents.forEach { a -> ChoiceRow(a.displayName, agentId == a.id) { agentId = a.id } }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    singleLine = true,
                    label = { Text("Title (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The session gets its own branch, so its work stays apart until you put it on main.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (blocked) {
                    Text(decision.reason ?: "The phone cannot start another agent right now.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(
                enabled = !starting && !blocked && projectId != null && agentId != null,
                onClick = {
                    val p = projectId ?: return@Button
                    val a = agentId ?: return@Button
                    starting = true
                    problem = null
                    scope.launch {
                        attempt { graph.sessions.start(p, a, title.trim().ifEmpty { null }) }
                            .onSuccess { onStarted(it.id) }
                            .onFailure { problem = "Could not start: ${plainReason(it)}" }
                        starting = false
                    }
                },
            ) {
                if (starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Start")
            }
        },
        dismissButton = { TextButton(enabled = !starting, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class AgentPanel(val title: String) { PREVIEW("Preview"), MEDIA("Media"), TERMINAL("Terminal") }

/**
 * The agent, full screen, in its own room on this session's worktree. A slim bar on top; the
 * agent's own screen below. Preview, Media and the terminal open over it without closing it.
 */
@Composable
fun AgentScreen(sessionId: String, nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val found = sessions.firstOrNull { it.id == sessionId }
    // A list refresh that briefly lacks the session must not tear down the agent's page.
    val lastSeen = remember(sessionId) { mutableStateOf(found) }
    SideEffect { if (found != null) lastSeen.value = found }
    val session = found ?: lastSeen.value

    if (session == null) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                EmptyState(Icons.Filled.Forum, "Session not found", "It may have been deleted, or it is still being restored from your Drive.")
            }
        }
        return
    }

    val agentId = session.agentId
    val agent = remember(agentId) { runCatching { graph.agents.find(agentId) }.getOrNull() }
    var opened by remember(sessionId) { mutableStateOf<RoomState?>(null) }
    var tries by remember(sessionId) { mutableIntStateOf(0) }
    var panel by remember(sessionId) { mutableStateOf<AgentPanel?>(null) }
    var showChanges by remember { mutableStateOf(false) }
    var putting by remember { mutableStateOf(false) }
    var bigDismissed by rememberSaveable(sessionId) { mutableStateOf(false) }
    var startingFresh by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, tries) {
        opened = null
        opened = attempt { graph.rooms.open(agentId, sessionId) }.getOrElse { RoomState.Failed(plainReason(it)) }
    }
    val view = roomView(opened, rooms[agentId], sessionId)
    val agentWeb = rememberWebViewHolder("agent:$sessionId")
    val terminal = rememberTerminalState("term:$sessionId")
    val preview = rememberPreviewState(sessionId)

    BackHandler(enabled = panel != null) { panel = null }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                AgentBar(
                    agent = agent,
                    session = session,
                    panelTitle = panel?.title,
                    onBack = { if (panel != null) panel = null else nav.back() },
                    onPanel = { panel = it },
                    onChanges = { showChanges = true },
                    onPutOnMain = { putting = true },
                    onStop = { stopping = true },
                )
                if (isLargeTranscript(session) && !bigDismissed && panel == null) {
                    BigChatBanner(
                        starting = startingFresh,
                        onFresh = {
                            startingFresh = true
                            scope.launch {
                                attempt { graph.sessions.start(session.projectId, agentId, null) }
                                    .onSuccess { nav.agent(it.id) }
                                    .onFailure { snackbar.showSnackbar("Could not start a session: ${plainReason(it)}") }
                                startingFresh = false
                            }
                        },
                        onDismiss = { bigDismissed = true },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    RoomContent(
                        view = view,
                        agentName = agentName(agent, agentId),
                        onRetry = { tries++ },
                        onBack = nav::back,
                    ) { url ->
                        AgentWebView(
                            url = url,
                            holder = agentWeb,
                            isInternal = graph.portBridge::isInternal,
                            onOpenExternal = nav::openExternal,
                            onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Panels cover the agent without removing it, so its page and sockets stay alive.
                    panel?.let { p ->
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            when (p) {
                                AgentPanel.PREVIEW -> PreviewPanel(sessionId, preview, nav, snackbar)
                                AgentPanel.MEDIA -> MediaPanel(sessionId, session.pendingVideos)
                                AgentPanel.TERMINAL -> TerminalPanel(sessionId, terminal, nav, snackbar)
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
        }
    }

    if (showChanges) ChangesSheet(session, onDismiss = { showChanges = false })
    if (putting) PutOnMainFlow(session, onClose = { putting = false })
    if (stopping) {
        ConfirmDialog(
            title = "Stop ${agentName(agent, agentId)}?",
            text = "Anything it is doing now stops. The session, its branch and its chat are kept; open it again any time.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = {
                scope.launch {
                    attempt { graph.rooms.stop(agentId) }
                        .onSuccess { nav.back() }
                        .onFailure { snackbar.showSnackbar("Could not stop the agent: ${plainReason(it)}") }
                }
            },
            onDismiss = { stopping = false },
        )
    }
}

@Composable
private fun AgentBar(
    agent: AgentInfo?,
    session: SessionRecord,
    panelTitle: String?,
    onBack: () -> Unit,
    onPanel: (AgentPanel) -> Unit,
    onChanges: () -> Unit,
    onPutOnMain: () -> Unit,
    onStop: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(if (panelTitle != null) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (panelTitle != null) "Close $panelTitle" else "Back")
            }
            AgentMark(agent, session.agentId, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    panelTitle ?: agentName(agent, session.agentId),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(session.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Session menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    AgentPanel.entries.forEach { p ->
                        DropdownMenuItem(text = { Text(p.title) }, onClick = { menu = false; onPanel(p) })
                    }
                    DropdownMenuItem(text = { Text("Changes") }, onClick = { menu = false; onChanges() })
                    if (session.status == SessionStatus.OPEN || session.status == SessionStatus.CONFLICT_COPY) {
                        DropdownMenuItem(text = { Text("Put on main") }, onClick = { menu = false; onPutOnMain() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Stop agent") }, onClick = { menu = false; onStop() })
                }
            }
        }
    }
}

@Composable
private fun BigChatBanner(starting: Boolean, onFresh: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(LARGE_TRANSCRIPT_WARNING, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(enabled = !starting, onClick = onFresh) { Text("Start fresh") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
        }
    }
}

@Composable
private fun RoomContent(
    view: RoomView,
    agentName: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    ready: @Composable (String) -> Unit,
) {
    when (view) {
        is RoomView.Ready -> ready(view.url)
        is RoomView.Opening -> CenterMessage(view.step ?: "Opening $agentName…", progress = true)
        is RoomView.Failed -> CenterMessage("$agentName did not start: ${view.why}") {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        RoomView.Elsewhere -> CenterMessage("$agentName's room is open on another session now.") {
            Button(onClick = onRetry) { Text("Open this session") }
        }
        RoomView.Stopped -> CenterMessage("$agentName stopped. It closes when idle to save memory; your session is saved.") {
            Button(onClick = onRetry) { Text("Start again") }
        }
    }
}

@Composable
private fun CenterMessage(text: String, progress: Boolean = false, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}
