package com.pocketide.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.agents.InstallStep
import com.pocketide.agents.InstalledExtension
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ide.Projects
import com.pocketide.linux.ComputerState
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.ExtensionLogo
import com.pocketide.ui.components.KeepTypedInput
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Home: the computer's state with its one set-up button, the project the agents work in, the
 * agents (each opens full screen), and the terminal.
 */
@Composable
@Suppress("LongParameterList") // One callback per place Home leads to.
fun HomeScreen(
    onOpenAgent: (String) -> Unit,
    onSignIn: (agentId: String, command: String, title: String) -> Unit,
    onTerminal: () -> Unit,
    onAddAgents: () -> Unit,
    onComputer: () -> Unit,
    onCloudShell: () -> Unit,
    onHelp: () -> Unit,
    onHelpPage: (String) -> Unit,
) {
    val graph = LocalContext.current.graph
    val computer by graph.computer.state.collectAsStateWithLifecycle()
    val installed by graph.agents.installed.collectAsStateWithLifecycle()
    val activity by graph.agents.activity.collectAsStateWithLifecycle()
    val problem by graph.agents.problem.collectAsStateWithLifecycle()
    val ready = computer == ComputerState.Ready || computer is ComputerState.Updating
    LaunchedEffect(ready) { if (ready) runCatching { graph.agents.refresh() } }
    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(36.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "PocketIDE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help") }
        }
        Text(DocsContent.TAGLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(16.dp)
        ComputerCard(computer, onSetUp = graph::setUp, onComputer = onComputer, onHelpPage = onHelpPage)
        if (ready) {
            Gap(12.dp)
            ProjectChip()
        }
        SectionLabel("Agents")
        AgentList(
            installed = installed,
            ready = ready,
            activity = activity,
            onOpen = onOpenAgent,
            onSignIn = onSignIn,
            onAddAgents = onAddAgents,
        )
        problem?.let {
            Gap(12.dp)
            NoticeCard(it, tone = Tone.ERROR, title = "The last install did not finish")
        }
        SectionLabel("Tools")
        OutlinedCard {
            ListRow(
                leading = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Terminal",
                subtitle = "Ubuntu's command line, in the project's folder",
                enabled = ready,
                onClick = onTerminal,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                leading = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Google Cloud Shell",
                subtitle = "Google's free Linux computer: VS Code and the agents, 50 hours a week",
                onClick = onCloudShell,
            )
        }
    }
}

@Composable
private fun ComputerCard(state: ComputerState, onSetUp: () -> Unit, onComputer: () -> Unit, onHelpPage: (String) -> Unit) {
    when (state) {
        ComputerState.NotInstalled -> OutlinedCard {
            Column(Modifier.padding(16.dp)) {
                Text("Set up your computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Gap(4.dp)
                Text(
                    "One tap installs Ubuntu 26.04 LTS, VS Code (code-server) and the three official agents inside PocketIDE. " +
                        "It downloads about 1 GB, needs about 3 GB free, and takes 15 to 30 minutes; Wi-Fi is best. " +
                        "If anything interrupts it, it continues where it stopped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12.dp)
                PrimaryAction("Set up", onClick = onSetUp)
                TextButton(onClick = { onHelpPage(DocsContent.COMPUTER_ID) }, modifier = Modifier.fillMaxWidth()) { Text("What is installed, and where") }
            }
        }
        is ComputerState.Installing -> OutlinedCard {
            Column(Modifier.padding(16.dp)) {
                Text("Setting up your computer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Gap(8.dp)
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Gap(8.dp)
                Text(state.step, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.bytesTotal > 0) {
                    Text(
                        "${megabytes(state.bytesDone)} of ${megabytes(state.bytesTotal)} downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Gap(4.dp)
                Text(
                    "You can leave the app: it keeps going, and a notification shows it is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is ComputerState.Broken -> Column {
            NoticeCard(state.fix, tone = Tone.ERROR, title = state.why)
            Gap(12.dp)
            PrimaryAction("Set up again", onClick = onSetUp)
        }
        ComputerState.Ready, is ComputerState.Updating -> OutlinedCard {
            ListRow(
                leading = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Your computer",
                subtitle = if (state is ComputerState.Updating) "Ready · updating ${state.what}" else "Ready · Ubuntu 26.04 LTS on this phone",
                onClick = onComputer,
            )
        }
    }
}

/** The project the agents open; tap to pick another or make a new one. */
@Composable
private fun ProjectChip() {
    val graph = LocalContext.current.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    Box {
        AssistChip(
            onClick = {
                projects = graph.projects.list()
                open = true
            },
            label = { Text("Project: " + settings.project.ifEmpty { "all projects" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All projects") }, onClick = {
                graph.settings.update { it.copy(project = "") }
                open = false
            })
            projects.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    graph.settings.update { it.copy(project = name) }
                    open = false
                })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New project…") },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    open = false
                    creating = true
                },
            )
        }
    }
    if (creating) {
        NewProjectDialog(
            onDone = { name ->
                graph.settings.update { it.copy(project = name) }
                creating = false
            },
            onCancel = { creating = false },
        )
    }
}

@Composable
private fun NewProjectDialog(onDone: (String) -> Unit, onCancel: () -> Unit) {
    val graph = LocalContext.current.graph
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = KeepTypedInput,
        title = { Text("New project") },
        text = {
            DialogBody {
                Text("A folder for one project. To work on a project from GitHub, make it here, then run git clone in the terminal.")
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.trim()
                        error = null
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = Projects.problem(name) ?: runCatching { graph.projects.create(name) }.exceptionOrNull()?.message
                if (error == null) onDone(name)
            }) { Text("Make it") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
@Suppress("LongParameterList") // The list, its state, and one callback per action on a row.
private fun AgentList(
    installed: List<InstalledExtension>,
    ready: Boolean,
    activity: InstallStep?,
    onOpen: (String) -> Unit,
    onSignIn: (String, String, String) -> Unit,
    onAddAgents: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val byId = installed.associateBy { it.id }
    val others = installed.filter { it.isAgent && it.official == null && it.id != COMPANION }
    OutlinedCard {
        Agent.entries.forEachIndexed { index, agent ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val have = byId[agent.extensionId]
            AgentRow(
                logo = { AgentLogo(agent, size = 40.dp) },
                name = agent.displayName,
                maker = agent.maker,
                installed = have != null,
                ready = ready,
                busy = activity != null,
                onOpen = { onOpen(agent.extensionId) },
                onInstall = { graph.installAgent(agent.publisher, agent.extensionName) },
                menu = listOf(
                    signInLabel(agent) to { onSignIn(agent.extensionId, agent.signInCommand, "Sign in: ${agent.displayName}") },
                ),
            )
        }
        others.forEach { extension ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AgentRow(
                logo = { ExtensionLogo(extension.displayName, size = 40.dp) },
                name = extension.displayName,
                maker = extension.id.substringBefore('.'),
                installed = true,
                ready = ready,
                busy = activity != null,
                onOpen = { onOpen(extension.id) },
                onInstall = {},
                menu = listOf("Remove" to { scope.launch { runCatching { graph.agents.remove(extension.id) } } }),
            )
        }
        activity?.let { step ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InstallProgress(step)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ListRow(
            leading = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(28.dp)) },
            title = "Add agents",
            subtitle = "More agents and extensions from Open VSX",
            enabled = ready,
            onClick = onAddAgents,
        )
    }
}

@Composable
@Suppress("LongParameterList") // A row shows one agent: what it is, its state, and what can be done with it.
private fun AgentRow(
    logo: @Composable () -> Unit,
    name: String,
    maker: String,
    installed: Boolean,
    ready: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(enabled = ready && installed, onClick = onOpen)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        logo()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(maker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            !installed -> FilledTonalButton(onClick = onInstall, enabled = ready && !busy) { Text("Install") }
            else -> Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (installed && menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = ready) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for $name") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            menuOpen = false
                            action()
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallProgress(step: InstallStep) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(14.dp))
        val text = when (step) {
            is InstallStep.Downloading -> if (step.total > 0) {
                "Downloading ${step.name}: ${megabytes(step.done)} of ${megabytes(step.total)}"
            } else {
                "Getting ${step.name} ready…"
            }
            is InstallStep.Installing -> "Installing ${step.name}…"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ListRow(leading: @Composable () -> Unit, title: String, subtitle: String?, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The terminal sign-in, named for what it signs in: the agent itself where its screen shares the
 * terminal's sign-in, else its command-line tool (Antigravity's screen signs in on its own).
 */
internal fun signInLabel(agent: Agent): String =
    if (agent.sharedSignInFile != null) "Sign in with the terminal" else "Sign in ${agent.signInCommand} (terminal only)"

/** Decimal megabytes, as Android's own storage screen counts them. */
internal fun megabytes(bytes: Long): String = String.format(Locale.ENGLISH, "%.0f MB", bytes / BYTES_PER_MB)

private const val BYTES_PER_MB = 1e6

private const val COMPANION = "pocketide.companion"
