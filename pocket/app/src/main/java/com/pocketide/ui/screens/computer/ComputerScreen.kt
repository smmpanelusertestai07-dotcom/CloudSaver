package com.pocketide.ui.screens.computer

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.InstallStep
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ide.IdeState
import com.pocketide.ide.UpdateStatus
import com.pocketide.linux.ComputerInfo
import com.pocketide.linux.ComputerState
import com.pocketide.linux.RepairItem
import com.pocketide.linux.RepairStatus
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.home.megabytes
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The computer: what it is, how much room it takes, its updates, and the four ways to put it
 * right (Restart, Repair, Reset, Delete), from the lightest to the heaviest.
 */
@Composable
fun ComputerScreen(onHelp: () -> Unit, onHelpPage: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val state by graph.computer.state.collectAsStateWithLifecycle()
    val ide by graph.ide.state.collectAsStateWithLifecycle()
    val update by graph.updater.status.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ComputerInfo?>(null) }
    var repair by remember { mutableStateOf<List<RepairItem>?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(state) { info = runCatching { graph.computer.info() }.getOrNull() }
    // What runs in the background now, counted again every few seconds while this screen shows.
    val installing by graph.agents.activity.collectAsStateWithLifecycle()
    var programs by remember { mutableIntStateOf(0) }
    LaunchedEffect(ide) {
        while (true) {
            programs = withContext(Dispatchers.IO) { runCatching { graph.computer.liveProcesses() }.getOrDefault(0) }
            delay(PROGRAMS_POLL_MS)
        }
    }
    val ready = state == ComputerState.Ready || state is ComputerState.Updating

    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Computer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help") }
        }
        Gap(12.dp)
        StatusCard(state, ide, onStop = { scope.launch { graph.stopEverything() } }, onSetUp = graph::setUp)
        RunningNow(ide, programs, installing, update)

        if (ready) {
            info?.let { facts ->
                SectionLabel("This computer")
                OutlinedCard {
                    Column(Modifier.padding(16.dp)) {
                        InfoRow("System", facts.ubuntu ?: "Ubuntu")
                        InfoRow("VS Code", facts.codeServer?.let { "code-server $it" } ?: "code-server")
                        InfoRow("Processor", "${facts.cpu} · ${facts.cores} cores")
                        InfoRow("Phone", facts.android)
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        InfoRow("Your projects, sign-ins and chats", Formats.size(facts.homeBytes))
                        InfoRow("Ubuntu and its programs", Formats.size(facts.systemBytes))
                        InfoRow("Free on the phone", Formats.size(facts.freeBytes))
                    }
                }
            }

            SectionLabel("Updates")
            OutlinedCard {
                Column(Modifier.padding(16.dp)) {
                    UpdateLines(update, settings.lastUpdate)
                    Gap(12.dp)
                    SecondaryAction(
                        "Update now",
                        onClick = { scope.launch { graph.updater.runNow() } },
                        enabled = update !is UpdateStatus.Running && state == ComputerState.Ready,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("Update on mobile data") },
                    supportingContent = { Text("Otherwise the daily updates wait for Wi-Fi.") },
                    trailingContent = {
                        Switch(checked = settings.updatesOnMobileData, onCheckedChange = { on -> graph.settings.update { it.copy(updatesOnMobileData = on) } })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            SectionLabel("Fix problems")
            Text(
                "Try these in order: each keeps your projects, sign-ins and chats.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(8.dp)
            SecondaryAction("Restart", onClick = { confirm = Confirm.RESTART }, enabled = !busy)
            Gap(8.dp)
            SecondaryAction("Repair", onClick = {
                busy = true
                scope.launch {
                    repair = runCatching { graph.computer.repair() }.getOrElse { listOf(RepairItem("Repair", RepairStatus.WARN, it.message ?: "Stopped.")) }
                    busy = false
                }
            }, enabled = !busy && state == ComputerState.Ready)
            Gap(8.dp)
            SecondaryAction("Reset Ubuntu", onClick = { confirm = Confirm.RESET }, enabled = !busy && state == ComputerState.Ready)
            if (busy) {
                Gap(8.dp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Gap(16.dp)
                NoticeCard(
                    "Android 12 and newer may stop an app's extra programs when too many run. If agents stop by themselves, " +
                        "see Help for the one-time fix.",
                    title = "If agents stop in the background",
                )
                TextButton(onClick = { onHelpPage(DocsContent.BACKGROUND_ID) }, modifier = Modifier.fillMaxWidth()) { Text("How to fix it") }
            }
            SectionLabel("Delete")
            SecondaryAction("Delete the computer", onClick = { confirm = Confirm.DELETE }, enabled = !busy)
        }
    }

    repair?.let { items -> RepairDialog(items, onClose = { repair = null }) }
    confirm?.let { which ->
        ConfirmDialog(
            which = which,
            onConfirm = {
                confirm = null
                busy = true
                graph.scope.launch {
                    try {
                        graph.stopEverything()
                        when (which) {
                            Confirm.RESTART -> graph.computer.stopAll()
                            Confirm.RESET -> graph.computer.reset()
                            Confirm.DELETE -> graph.computer.remove()
                        }
                    } finally {
                        busy = false
                    }
                }
            },
            onCancel = { confirm = null },
        )
    }
}

private enum class Confirm { RESTART, RESET, DELETE }

@Composable
private fun StatusCard(state: ComputerState, ide: IdeState, onStop: () -> Unit, onSetUp: () -> Unit) {
    val (label, tone) = when (state) {
        ComputerState.NotInstalled -> "Not set up" to Tone.NEUTRAL
        is ComputerState.Installing -> "Setting up" to Tone.WARN
        ComputerState.Ready -> "Ready" to Tone.OK
        is ComputerState.Updating -> "Updating" to Tone.WARN
        is ComputerState.Broken -> "Needs attention" to Tone.ERROR
    }
    OutlinedCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ubuntu on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusChip(label, tone)
            }
            when (state) {
                is ComputerState.Installing -> {
                    Gap(8.dp)
                    val fraction = state.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Gap(6.dp)
                    Text(state.step, style = MaterialTheme.typography.bodyMedium)
                }
                is ComputerState.Broken -> {
                    Gap(8.dp)
                    NoticeCard(state.fix, tone = Tone.ERROR, title = state.why)
                    Gap(8.dp)
                    PrimaryAction("Set up again", onClick = onSetUp)
                }
                ComputerState.NotInstalled -> {
                    Gap(8.dp)
                    Text("Set it up on Home with one tap.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> Unit
            }
            if (ide is IdeState.On || ide is IdeState.Starting) {
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ide is IdeState.On) "code-server is on: the agents keep working in the background." else "code-server is starting…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
            if (ide is IdeState.Failed) {
                Gap(12.dp)
                NoticeCard(ide.fix, tone = Tone.WARN, title = ide.why)
            }
        }
    }
}

/**
 * What keeps the phone busy in the background, in plain words: code-server and the programs the
 * agents started (the ongoing notification's Stop ends them), an agent's download, the updates.
 */
@Composable
private fun RunningNow(ide: IdeState, programs: Int, installing: InstallStep?, update: UpdateStatus) {
    val lines = buildList {
        if (ide is IdeState.On) add(runningPrograms(programs))
        installing?.let { step ->
            add(
                when (step) {
                    is InstallStep.Downloading ->
                        if (step.total > 0) "Downloading ${step.name}: ${megabytes(step.done)} of ${megabytes(step.total)}." else "Getting ${step.name} ready."
                    is InstallStep.Installing -> "Installing ${step.name}."
                },
            )
        }
        if (update is UpdateStatus.Running) add("Updating: ${update.step}")
    }
    if (lines.isEmpty()) return
    SectionLabel("Running now")
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** code-server and the programs under it; an agent's first start also fetches its own tool (Antigravity's agy). */
internal fun runningPrograms(programs: Int): String = when {
    programs <= 0 -> "code-server is on."
    programs == 1 -> "code-server is on: 1 program is running."
    else -> "code-server is on: $programs programs are running (code-server, and what the agents started)."
}

private const val PROGRAMS_POLL_MS = 5_000L

@Composable
private fun UpdateLines(status: UpdateStatus, lastUpdate: Long) {
    val graph = LocalContext.current.graph
    when (status) {
        is UpdateStatus.Running -> {
            Text(status.step, style = MaterialTheme.typography.bodyMedium)
            Gap(8.dp)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is UpdateStatus.Done -> {
            Text("Checked ${Formats.ago(status.at, graph.clock.now())}", style = MaterialTheme.typography.titleSmall)
            status.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        UpdateStatus.Idle -> Text(
            "Ubuntu's security fixes, the agents and code-server update by themselves once a day while the computer is on. " +
                "Last checked: ${Formats.ago(lastUpdate.takeIf { it > 0 }, graph.clock.now())}.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RepairDialog(items: List<RepairItem>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Repair") },
        text = {
            DialogBody(spacing = 10.dp) {
                items.forEach { item ->
                    val tone = when (item.status) {
                        RepairStatus.OK -> Tone.OK
                        RepairStatus.NEW -> Tone.OK
                        RepairStatus.WARN -> Tone.ERROR
                        RepairStatus.NOTE -> Tone.NEUTRAL
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.what, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusChip(
                            when (item.status) {
                                RepairStatus.OK -> "OK"
                                RepairStatus.NEW -> "Fixed"
                                RepairStatus.WARN -> "Not fixed"
                                RepairStatus.NOTE -> "Note"
                            },
                            tone,
                        )
                    }
                    Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun ConfirmDialog(which: Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val graph = LocalContext.current.graph
    val plan = graph.computer.resetPlan()
    val (title, body, action) = when (which) {
        Confirm.RESTART -> Triple(
            "Restart the computer?",
            "Every program on it stops: code-server, the agents, and anything they run. A task an agent is doing stops too. " +
                "Files, sign-ins and chats stay.",
            "Restart",
        )
        Confirm.RESET -> Triple(
            "Reset Ubuntu?",
            "Deletes: " + plan.removes.joinToString("; ") + ".\n\nKeeps: " + plan.keeps.joinToString("; ") +
                ".\n\nUbuntu is then set up again, as new (a download of about 350 MB).",
            "Reset",
        )
        Confirm.DELETE -> Triple(
            "Delete the computer?",
            "Deletes everything on it: Ubuntu, your projects, each agent's sign-in, settings and chats, and the installed agents. " +
                "This cannot be undone. Move anything you want to keep to GitHub first.",
            "Delete everything on it",
        )
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { DialogBody { Text(body) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
