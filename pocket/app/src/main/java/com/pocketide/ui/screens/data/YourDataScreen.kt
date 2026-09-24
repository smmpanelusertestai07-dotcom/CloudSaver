package com.pocketide.ui.screens.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.model.AgentInfo
import com.pocketide.model.SessionRecord
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.AsOfLine
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.DataMath
import com.pocketide.ui.manage.DiskUsage
import com.pocketide.ui.manage.ErrorNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.LinkRow
import com.pocketide.ui.manage.ManageFormat
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.ManageText
import com.pocketide.ui.manage.MemoryFile
import com.pocketide.ui.manage.MemoryFiles
import com.pocketide.ui.manage.NavRow
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.manage.rememberLoad
import com.pocketide.ui.nav.PocketNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a kind of data is kept. */
private enum class Place(val label: String) { PHONE("Phone"), DRIVE("Drive"), GITHUB("GitHub") }

/** Sizes read from this phone's storage. */
private data class PhoneSizes(
    val computer: Long,
    val projects: Long,
    val builds: Long,
    val memory: Map<String, List<MemoryFile>>,
) {
    val memoryBytes: Long get() = memory.values.flatten().sumOf { it.bytes }
    val memoryCount: Int get() = memory.values.flatten().count { it.exists }
}

private const val DELETE_EVERYTHING = "delete-everything"
private const val MOVE = "move-account"

/**
 * Everything PocketIDE keeps, by type and by place (phone, Drive, GitHub), the largest sessions,
 * the agents' instructions and memory (editable), Variables and Secrets, how long things are
 * kept, moving to another Google account, deleting everything, and how to do it without the app.
 */
@Composable
fun YourDataScreen(nav: PocketNav) {
    val graph = rememberGraph()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MemoryFile?>(null) }
    val file = editing
    val agent = file?.let { f -> agents.firstOrNull { it.id == f.agentId } }
    if (file != null) {
        MemoryEditor(graph, graph.dirs.roomHome(file.agentId), file, agent?.displayName ?: file.agentId, nav) { editing = null }
    } else {
        DataOverview(graph, agents, nav) { editing = it }
    }
}

@Composable
private fun DataOverview(graph: AppGraph, agents: List<AgentInfo>, nav: PocketNav, onEdit: (MemoryFile) -> Unit) {
    val runner = rememberActionRunner()
    val sessions by graph.sessions.all.collectAsStateWithLifecycle()
    val projects by graph.projects.all.collectAsStateWithLifecycle()
    val secrets by graph.secrets.values.collectAsStateWithLifecycle()
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val now = graph.clock::now
    val sizes = rememberLoad(agents, now) { readSizes(graph, agents) }
    val drive = rememberLoad("drive", now) { graph.usage.google() }
    var removeMediaOf by remember { mutableStateOf<SessionRecord?>(null) }
    var confirmMove by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val largest = remember(sessions) { DataMath.largestSessions(sessions) }

    ManagePage("Your data", nav, runner) {
        item { SectionLabel("Where it lives") }
        item {
            SectionCard(null) {
                InfoRow("This phone", if (snapshot.appDataBytes > 0) ManageFormat.bytes(snapshot.appDataBytes) else "…")
                InfoRow(
                    "Google Drive (hidden, encrypted)",
                    drive.value?.let { ManageFormat.bytes(it.appDataBytes) } ?: if (drive.loading) "…" else "Unknown",
                )
                InfoRow("GitHub", ManageFormat.count(projects.size, "private repository", "private repositories") + " + keyring")
                drive.error?.let { ErrorNote(it) }
                AsOfLine(drive.at, drive.loading || sizes.loading) {
                    drive.refresh()
                    sizes.refresh()
                }
            }
        }
        item { SectionLabel("By type") }
        item { ByTypeCard(sessions, projects.size, secrets.size, sizes.value) }
        item { SectionLabel("Largest sessions") }
        item { LargestCard(largest, nav) { removeMediaOf = it } }
        item { SectionLabel("Memory and instructions") }
        item { MemoryCard(agents, sizes.value?.memory, sizes.loading, onEdit) }
        item {
            SectionCard(null) {
                NavRow(Icons.Outlined.Key, "Variables and Secrets", "${secrets.size} saved · values are masked") { nav.secrets(null) }
                NavRow(Icons.Outlined.RestoreFromTrash, "Recently deleted", "Chats you deleted in the last 30 days") { nav.recentlyDeleted() }
            }
        }
        item { SectionLabel("How long things are kept") }
        item {
            SectionCard(null) {
                ManageText.retention(settings).forEach { (what, rule) -> InfoRow(what, rule) }
                NavRow(Icons.Outlined.Tune, "Change in Settings", null, nav::settings)
            }
        }
        item { SectionLabel("Move to another Google account") }
        item { MoveCard(runner) { confirmMove = true } }
        item { SectionLabel("Without the app") }
        item { WithoutAppCard(nav) }
        item { SectionLabel("Delete everything") }
        item {
            SectionCard(null) {
                Hint(
                    "Erases your chats, media, memory, settings, Variables and Secrets from this phone and from your Drive, and the " +
                        "key halves. Your code stays in your GitHub repositories. This cannot be undone.",
                )
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !runner.isBusy(DELETE_EVERYTHING),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (runner.isBusy(DELETE_EVERYTHING)) "Deleting…" else "Delete everything")
                }
            }
        }
    }

    removeMediaOf?.let { session ->
        ConfirmDialog(
            title = "Remove media from \"${session.title}\"?",
            text = "${ManageFormat.count(session.mediaCount, "file")} (${ManageFormat.bytes(session.mediaBytes)}) are removed from this " +
                "phone and your Drive. The chat itself stays.",
            confirmLabel = "Remove media",
            destructive = true,
            onConfirm = { runner.run("media:${session.id}", done = "Media removed. The chat is kept.") { graph.sessions.removeMedia(session.id) } },
            onDismiss = { removeMediaOf = null },
        )
    }
    if (confirmMove) {
        ConfirmDialog(
            title = "Move to another Google account?",
            text = "You choose the new account in Google's own screen. Keep the app open on Wi-Fi until it finishes; " +
                "your old account's copy is erased only after you agree at the end.",
            confirmLabel = "Start",
            destructive = false,
            onConfirm = {
                runner.run(MOVE, done = "Everything is in the new Google account.", outlivesScreen = true) { graph.sync.moveToAnotherAccount() }
            },
            onDismiss = { confirmMove = false },
        )
    }
    if (confirmDelete) DeleteEverythingDialog(onDismiss = { confirmDelete = false }) {
        runner.run(DELETE_EVERYTHING, done = "Everything was deleted.", outlivesScreen = true) { graph.sync.deleteEverything() }
    }
}

private suspend fun readSizes(graph: AppGraph, agents: List<AgentInfo>): PhoneSizes = withContext(Dispatchers.IO) {
    val dirs = graph.dirs
    val memory = agents.associate { agent ->
        agent.id to MemoryFiles.find(dirs.roomHome(agent.id), agent.id, agent.instructionsFile)
    }
    PhoneSizes(
        computer = runCatching { graph.computer.sizeBytes() }.getOrDefault(0L),
        // Session media lives beside the worktrees; it is counted under Media, not twice.
        projects = DiskUsage.sizeOf(dirs.repos) + DiskUsage.sizeOf(dirs.work, skipDirectory = ".media"),
        builds = DiskUsage.sizeOf(dirs.builds),
        memory = memory,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeRow(title: String, value: String, places: List<Place>, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            places.forEach { StatusChip(it.label, Tone.NEUTRAL) }
        }
        if (note != null) Hint(note)
    }
}

@Composable
private fun ByTypeCard(sessions: List<SessionRecord>, projectCount: Int, secretCount: Int, sizes: PhoneSizes?) {
    val live = DataMath.liveSessions(sessions)
    val pending = "…"
    SectionCard(null) {
        TypeRow(
            "Chats",
            "${ManageFormat.count(live.size, "session")} · ${ManageFormat.bytes(DataMath.chatBytes(sessions))}",
            listOf(Place.PHONE, Place.DRIVE),
            "All in Drive; the phone keeps recent ones. Never in GitHub.",
        )
        HorizontalDivider()
        TypeRow(
            "Media",
            "${ManageFormat.count(DataMath.mediaCount(sessions), "file")} · ${ManageFormat.bytes(DataMath.mediaBytes(sessions))}",
            listOf(Place.PHONE, Place.DRIVE),
            "Screenshots, videos and files in your chats.",
        )
        HorizontalDivider()
        TypeRow(
            "Memory and instructions",
            sizes?.let { "${ManageFormat.count(it.memoryCount, "file")} · ${ManageFormat.bytes(it.memoryBytes)}" } ?: pending,
            listOf(Place.PHONE, Place.DRIVE),
        )
        HorizontalDivider()
        TypeRow(
            "Variables and Secrets",
            ManageFormat.count(secretCount, "value"),
            listOf(Place.PHONE, Place.DRIVE),
            "Encrypted. GitHub gets a Secret only when you send it for builds.",
        )
        HorizontalDivider()
        TypeRow("Projects", "$projectCount · ${sizes?.let { ManageFormat.bytes(it.projects) } ?: pending} here", listOf(Place.GITHUB, Place.PHONE), "GitHub holds the main copy; the phone holds a working copy.")
        HorizontalDivider()
        TypeRow("Build outputs", sizes?.let { ManageFormat.bytes(it.builds) } ?: pending, listOf(Place.PHONE, Place.GITHUB), "The last 3 per project here; GitHub keeps artifacts for 7 days.")
        HorizontalDivider()
        TypeRow("The computer", sizes?.let { ManageFormat.bytes(it.computer) } ?: pending, listOf(Place.PHONE), "Rebuildable any time; nothing in it is the only copy.")
        HorizontalDivider()
        TypeRow("Agent sign-ins", "On this phone only", listOf(Place.PHONE), "Never synced. After a new phone or a reset you sign in to each agent again.")
    }
}

@Composable
private fun LargestCard(largest: List<SessionRecord>, nav: PocketNav, onRemoveMedia: (SessionRecord) -> Unit) {
    SectionCard(null) {
        if (largest.isEmpty()) Hint("No sessions yet.")
        largest.forEachIndexed { index, session ->
            if (index > 0) HorizontalDivider()
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.transcript(session.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Hint("${session.projectId.substringAfter('/')} · ${session.agentId}")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(ManageFormat.bytes(DataMath.sizeOf(session)), style = MaterialTheme.typography.bodyMedium)
                }
                if (session.mediaBytes > 0) {
                    TextButton(onClick = { onRemoveMedia(session) }) {
                        Icon(Icons.Outlined.HideImage, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Remove media, keep chat (${ManageFormat.bytes(session.mediaBytes)})")
                    }
                }
            }
        }
        if (largest.isNotEmpty()) Hint("Deleting a whole session is in Chats; it goes to Recently deleted for 30 days.")
    }
}

@Composable
private fun MemoryCard(agents: List<AgentInfo>, memory: Map<String, List<MemoryFile>>?, loading: Boolean, onEdit: (MemoryFile) -> Unit) {
    SectionCard(null) {
        Hint("What each agent always reads, and what it remembers. Kept outside your repositories and synced, encrypted, to Drive.")
        if (memory == null) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Hint("Could not read the rooms.")
            return@SectionCard
        }
        if (agents.isEmpty()) Hint("No agents yet.")
        agents.forEach { agent ->
            val files = memory[agent.id].orEmpty()
            Text(agent.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            if (files.isEmpty()) Hint("Appears once this agent's room is set up.")
            files.forEach { f ->
                NavRow(
                    Icons.AutoMirrored.Outlined.Article,
                    f.label,
                    if (f.exists) ManageFormat.bytes(f.bytes) else "Not created yet · tap to write one",
                ) { onEdit(f) }
            }
        }
    }
}

@Composable
private fun MoveCard(runner: ActionRunner, onStart: () -> Unit) {
    SectionCard(null) {
        Text("1. Sign in to the new Google account.", style = MaterialTheme.typography.bodyMedium)
        Text("2. The app copies every file of your vault, one at a time, so the phone never needs double space.", style = MaterialTheme.typography.bodyMedium)
        Text("3. It makes a new key half there, checks everything, then asks whether to erase the old account's copy.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onStart, enabled = !runner.isBusy(MOVE)) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (runner.isBusy(MOVE)) "Moving…" else "Move to another account")
        }
        if (runner.isBusy(MOVE)) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun WithoutAppCard(nav: PocketNav) {
    SectionCard(null) {
        Hint("Drive's hidden folder cannot be opened or edited, by design: it is hidden and every file is encrypted. You can still check it or remove it:")
        Text(
            "See the size: on drive.google.com (a computer, or \"Desktop site\" in a phone browser) open Settings → Manage apps; " +
                "PocketIDE shows its hidden app data.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Delete it all: in the same place choose Options → Delete hidden app data, then Disconnect from Drive.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinkRow("Drive settings → Manage apps", "https://drive.google.com/drive/settings", nav)
        LinkRow("What uses your Google storage", "https://one.google.com/storage", nav)
        HorizontalDivider()
        Text("Remove GitHub access: GitHub → Settings → Applications → revoke PocketIDE. Delete pocketide-keyring too if you want.", style = MaterialTheme.typography.bodyMedium)
        LinkRow("GitHub applications", "https://github.com/settings/applications", nav)
        Text("Remove Google access: your Google Account → Security → third-party connections → PocketIDE.", style = MaterialTheme.typography.bodyMedium)
        LinkRow("Google third-party connections", "https://myaccount.google.com/connections", nav)
        Hint("The Drive phone app does not have Manage apps; the website does.")
    }
}

@Composable
private fun DeleteEverythingDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = "Delete everything?",
        text = "Chats, media, memory, settings, Variables and Secrets are erased from this phone and your Drive. Your GitHub " +
            "repositories are not touched. Type DELETE to confirm.",
        confirmLabel = "Delete everything",
        destructive = true,
        confirmEnabled = DataMath.deleteConfirmed(typed),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extra = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Type DELETE") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
