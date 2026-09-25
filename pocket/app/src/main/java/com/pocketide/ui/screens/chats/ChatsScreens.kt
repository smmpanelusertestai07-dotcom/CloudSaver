package com.pocketide.ui.screens.chats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Ist
import com.pocketide.model.SessionRecord
import com.pocketide.rooms.RoomState
import com.pocketide.sessions.TranscriptEntry
import com.pocketide.sync.SessionBackup
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.manage.BackgroundLimitNote
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.project.ConfirmDialog
import com.pocketide.ui.screens.project.EmptyState
import com.pocketide.ui.screens.project.MediaStrip
import com.pocketide.ui.screens.project.SectionLabel
import com.pocketide.ui.screens.project.WorkFormat
import com.pocketide.ui.screens.project.act
import com.pocketide.ui.screens.project.agentName
import com.pocketide.ui.screens.project.attempt
import com.pocketide.ui.screens.project.finish
import com.pocketide.ui.screens.project.plainReason
import com.pocketide.ui.screens.project.rememberGraph
import com.pocketide.ui.screens.project.sessionStatusLabel
import com.pocketide.ui.screens.project.waitingVideosText
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * All chat sessions, on the phone and in Drive, with search and filters. Reading is read-only;
 * a chat can be renamed, continued in its agent, put on main or deleted (to Recently deleted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val rooms by graph.rooms.states.collectAsStateWithLifecycle()
    val waiting by graph.sync.waiting.collectAsStateWithLifecycle()
    val backups by graph.sync.backups.collectAsStateWithLifecycle()
    val backgroundLimit by graph.sync.backgroundLimit.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var agentId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    val nameOf = { id: String -> agentName(agents.firstOrNull { it.id == id }, id) }
    val shown = remember(sessions, query, agentId, projectId, status, agents) {
        filterChats(sessions, ChatFilter(query, agentId, projectId, status), nameOf)
    }
    val deletedCount = remember(sessions) { recentlyDeleted(sessions).size }
    var dialog by remember { mutableStateOf<Pair<ChatDialog, String>?>(null) }

    LaunchedEffect(Unit) { attempt { graph.sessions.refresh() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search chats") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "filters") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusFilter.entries.forEach { f ->
                        FilterChip(selected = status == f, onClick = { status = f }, label = { Text(f.label) })
                    }
                    PickerChip("Agent", agentId?.let(nameOf), agents.map { it.id to it.displayName }) { agentId = it }
                    PickerChip("Project", projectId, projects.map { it.id to it.id }) { projectId = it }
                }
            }
            backgroundLimit?.let { limit -> item(key = "background") { BackgroundLimitNote(limit) } }
            item(key = "places") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = nav::recentlyDeleted) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (deletedCount > 0) "Recently deleted ($deletedCount)" else "Recently deleted")
                    }
                    OutlinedButton(onClick = nav::waitingUploads) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (waiting.isNotEmpty()) "Waiting to upload (${waiting.size})" else "Waiting to upload")
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Filled.Forum,
                        if (sessions.isEmpty()) "No chats yet" else "No chat matches",
                        if (sessions.isEmpty()) {
                            "Every session you start with an agent appears here, with its date, project and size."
                        } else {
                            "Try another search or clear the filters."
                        },
                    )
                }
            }
            items(shown, key = { it.id }) { session ->
                val room = rooms[session.agentId]
                ChatRow(
                    session = session,
                    agentLabel = nameOf(session.agentId),
                    running = room is RoomState.Running && room.sessionId == session.id,
                    backup = backups[session.id],
                    nav = nav,
                    snackbar = snackbar,
                    scope = scope,
                    onDialog = { dialog = it to session.id },
                )
            }
        }
    }

    dialog?.let { (which, id) ->
        // Looked up in every chat, not the filtered list: a chat put on main may leave the filter mid-way.
        val session = sessions.firstOrNull { it.id == id }
        if (session == null) {
            LaunchedEffect(id) { dialog = null }
        } else {
            ChatDialogHost(which, session, nav, snackbar, scope, onClose = { dialog = null })
        }
    }
}

@Composable
private fun PickerChip(label: String, current: String?, options: List<Pair<String, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { open = false; onPick(null) })
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

/** A chat, read-only: its details, its media and every message, selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(sessionId: String, nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val backups by graph.sync.backups.collectAsStateWithLifecycle()
    val session = sessions.firstOrNull { it.id == sessionId }
    var entries by remember(sessionId) { mutableStateOf<Result<List<TranscriptEntry>>?>(null) }
    var tries by remember(sessionId) { mutableIntStateOf(0) }
    val agent = session?.let { s -> agents.firstOrNull { it.id == s.agentId } }
    val agentLabel = session?.let { agentName(agent, it.agentId) } ?: "Agent"

    LaunchedEffect(sessionId, tries) {
        entries = null
        var result = attempt { graph.sessions.transcript(sessionId) }
        if (result.isFailure) {
            // An older chat may be only in Drive; it is downloaded when opened.
            result = attempt {
                graph.sync.fetchSession(sessionId)
                graph.sessions.transcript(sessionId)
            }
        }
        entries = result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: "Chat", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (session != null && session.canContinue()) {
                        TextButton(onClick = {
                            scope.act(snackbar, "Could not continue", then = { nav.agent(sessionId) }) {
                                graph.sessions.continueSession(sessionId)
                            }
                        }) { Text("Continue") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session != null) {
                item(key = "details") { SessionDetails(session, agentLabel, backups[session.id]) }
                item(key = "media") { MediaStrip(sessionId) }
            }
            item(key = "label") {
                SectionLabel("Messages")
                Text(
                    "Read-only. The messages themselves are not editable, so the agent can always continue this chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val result = entries
            when {
                result == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                result.isFailure -> item(key = "failed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Could not open this chat: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}")
                        Button(onClick = { tries++ }) { Text("Try again") }
                    }
                }
                result.getOrThrow().isEmpty() -> item(key = "none") { Text("No messages yet.") }
                else -> items(result.getOrThrow()) { entry -> MessageCard(entry, agentLabel) }
            }
        }
    }
}

@Composable
private fun SessionDetails(session: SessionRecord, agentLabel: String, backup: SessionBackup?) {
    val (status, _) = sessionStatusLabel(session.status, running = false)
    SectionCard(title = "Details") {
        InfoRow("Agent", agentLabel)
        InfoRow("Project", session.projectId)
        InfoRow("Branch", session.branch)
        InfoRow("Status", status)
        InfoRow("Started", Ist.dateTime(session.startedAt))
        InfoRow("Last activity", Ist.dateTime(session.lastActivityAt))
        InfoRow("Commits", session.commits.toString())
        InfoRow("Files changed", session.filesChanged.toString())
        if (session.tokensIn > 0 || session.tokensOut > 0) {
            InfoRow("Tokens", "${grouped(session.tokensIn)} in · ${grouped(session.tokensOut)} out")
        }
        InfoRow("Size", WorkFormat.bytes(sessionBytes(session)))
        InfoRow("Backup", backupState(session, backup).first)
    }
}

private fun grouped(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)

private fun roleLabel(role: String, agentLabel: String): String = when (role.lowercase()) {
    "user", "human", "you" -> "You"
    "assistant", "agent", "model" -> agentLabel
    "tool", "function" -> "Tool"
    "system" -> "System"
    else -> role.replaceFirstChar { it.uppercase() }
}

@Composable
private fun MessageCard(entry: TranscriptEntry, agentLabel: String) {
    val you = roleLabel(entry.role, agentLabel) == "You"
    Surface(
        color = if (you) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel(entry.role, agentLabel), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                entry.at?.let { Text(Ist.dateTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (entry.text.isNotBlank()) SelectableText(entry.text, Modifier.fillMaxWidth())
            if (entry.imageCount > 0) {
                StatusChip(
                    "${WorkFormat.count(entry.imageCount, "image", "images")} · shown in the agent's own screen",
                    Tone.NEUTRAL,
                )
            }
        }
    }
}

/** Chats deleted in the last 30 days: restore them, or erase them now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val deleted = remember(sessions) { recentlyDeleted(sessions) }
    var erasing by remember { mutableStateOf<SessionRecord?>(null) }
    var erasingAll by remember { mutableStateOf(false) }
    val now = remember(sessions) { graph.clock.now() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recently deleted") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (deleted.isNotEmpty()) {
                        IconButton(onClick = { erasingAll = true }) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete all forever") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "explain") {
                SectionCard(title = null) {
                    Text(
                        "Deleted chats stay here for $RECENTLY_DELETED_DAYS days, then they are erased from your Drive for good. " +
                            "The count is kept in your Drive, so reinstalling the app does not restart it. " +
                            "They can be restored only here, not from Drive's own Trash.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (deleted.isEmpty()) {
                item(key = "empty") { EmptyState(Icons.Filled.RestoreFromTrash, "Nothing here", "Chats you delete appear here for 30 days.") }
            }
            items(deleted, key = { it.id }) { session ->
                val days = session.deletedAt?.let { daysLeft(it, now) } ?: RECENTLY_DELETED_DAYS
                SectionCard(title = null) {
                    Text(session.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${agentName(agents.firstOrNull { it.id == session.agentId }, session.agentId)} · ${session.projectId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        session.deletedAt?.let {
                            Text("Deleted ${Ist.date(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(daysLeftText(days), if (days <= 3) Tone.WARN else Tone.NEUTRAL)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.act(snackbar, "Could not restore", done = "Restored to Chats.") { graph.sessions.restore(session.id) } }) {
                            Text("Restore")
                        }
                        TextButton(onClick = { erasing = session }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    erasing?.let { session ->
        ConfirmDialog(
            title = "Delete \"${session.title}\" forever?",
            text = "It is erased from this phone and from your Drive now. This cannot be undone.",
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = { scope.act(snackbar, "Could not delete", done = "Deleted forever.") { graph.sessions.deleteForever(session.id) } },
            onDismiss = { erasing = null },
        )
    }
    if (erasingAll) {
        ConfirmDialog(
            title = "Delete all forever?",
            text = "All ${WorkFormat.count(deleted.size, "chat", "chats")} here are erased from this phone and from your Drive now. This cannot be undone.",
            confirmLabel = "Delete all forever",
            destructive = true,
            onConfirm = {
                val ids = deleted.map { it.id }
                scope.launch {
                    val failures = finish { ids.count { id -> attempt { graph.sessions.deleteForever(id) }.isFailure } }.getOrDefault(ids.size)
                    snackbar.showSnackbar(if (failures == 0) "Deleted forever." else "${WorkFormat.count(failures, "chat", "chats")} could not be deleted. Try again.")
                }
            },
            onDismiss = { erasingAll = false },
        )
    }
}

/**
 * Chats not yet confirmed in Drive, each with its size: upload the chosen ones now, or keep
 * them on this phone only (clearly marked "not backed up").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingUploadsScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val waiting by graph.sync.waiting.collectAsStateWithLifecycle()
    val status by graph.sync.status.collectAsStateWithLifecycle()
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var keepLocal by remember { mutableStateOf(false) }
    val waitingIds = remember(waiting) { waiting.map { it.sessionId }.toSet() }
    val selected = chosen intersect waitingIds
    val phoneOnly = remember(sessions) { sessions.filter { !it.backUp && it.deletedAt == null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Waiting to upload") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (waiting.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(enabled = selected.isNotEmpty(), onClick = { keepLocal = true }, modifier = Modifier.weight(1f)) {
                            Text("Keep on this phone only", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val ids = selected.toList()
                                scope.act(snackbar, "Could not upload", done = "Uploading ${WorkFormat.count(ids.size, "chat", "chats")}.") {
                                    graph.sync.uploadNow(ids)
                                }
                                chosen = emptySet()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Upload now") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "status") {
                SectionCard(title = null) {
                    Text(syncLine(status), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Chats wait safely on this phone until your Drive confirms them. Videos wait for Wi-Fi unless Settings allow mobile data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (waiting.isEmpty()) {
                item(key = "empty") { EmptyState(Icons.Filled.CloudUpload, "Nothing waiting", "Every chat that is backed up is safe in your Drive.") }
            } else {
                item(key = "all") {
                    val all = selected.size == waitingIds.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = all, onCheckedChange = { chosen = if (all) emptySet() else waitingIds })
                        Text("Select all · ${WorkFormat.bytes(waiting.sumOf { it.bytes })}", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(waiting.distinctBy { it.sessionId }, key = { it.sessionId }) { upload ->
                    val checked = upload.sessionId in selected
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { on -> chosen = if (on) chosen + upload.sessionId else chosen - upload.sessionId })
                        Column(Modifier.weight(1f)) {
                            Text(upload.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(WorkFormat.bytes(upload.bytes), waitingVideosText(upload.videos)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (phoneOnly.isNotEmpty()) {
                item(key = "local-label") { SectionLabel("On this phone only") }
                items(phoneOnly, key = { "local:${it.id}" }) { session ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StatusChip("Not backed up", Tone.WARN)
                        }
                        TextButton(onClick = {
                            scope.act(snackbar, "Could not change it", done = "It will be backed up to your Drive.") { graph.sessions.setBackUp(session.id, true) }
                        }) { Text("Back up again") }
                    }
                }
            }
        }
    }

    if (keepLocal) {
        ConfirmDialog(
            title = "Keep on this phone only?",
            text = "${WorkFormat.count(selected.size, "chat", "chats")} will not be backed up to your Drive. " +
                "If this phone is lost or reset, they are gone. You can back them up again later.",
            confirmLabel = "Keep on phone only",
            destructive = true,
            onConfirm = {
                val ids = selected.toList()
                scope.launch {
                    val failures = finish { ids.count { id -> attempt { graph.sessions.setBackUp(id, false) }.isFailure } }.getOrDefault(ids.size)
                    snackbar.showSnackbar(if (failures == 0) "Kept on this phone only. Marked \"Not backed up\"." else "Some chats could not be changed. Try again.")
                }
                chosen = emptySet()
            },
            onDismiss = { keepLocal = false },
        )
    }
}

private fun syncLine(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "Backup is idle."
    is SyncStatus.Running -> "Backing up: ${status.what}"
    is SyncStatus.UpToDate -> "Backed up ${Ist.dateTime(status.at)}."
    is SyncStatus.Waiting -> "Waiting since ${Ist.dateTime(status.since)}: ${status.why}"
    is SyncStatus.Error -> "Backup problem: ${status.why}"
}
