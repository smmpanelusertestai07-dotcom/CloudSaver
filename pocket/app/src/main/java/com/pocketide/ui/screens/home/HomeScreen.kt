package com.pocketide.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.cloud.Computer
import com.pocketide.cloud.ComputerService
import com.pocketide.cloud.ComputersView
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.GitHubLogo
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.screens.computer.label
import com.pocketide.ui.screens.computer.machineLine
import com.pocketide.ui.screens.computer.tone
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.web.Browser
import com.pocketide.usage.CloudUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The owner's day starts here: the three agents, the cloud computers (read live from GitHub),
 * new projects, and this month's hours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenComputer: (Agent?) -> Unit,
    onNewProject: () -> Unit,
    onOpenRepo: () -> Unit,
    onUsage: () -> Unit,
    onHelp: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val view by graph.computers.view.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Computer?>(null) }
    var needsProject by remember { mutableStateOf(false) }

    val refresh: () -> Unit = {
        scope.launch {
            refreshing = true
            problem = runCatchingMessage { graph.computers.refresh() }
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val usage by produceState<CloudUsage?>(null) {
        value = try {
            graph.usage.read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }
    }

    val computers = when (val v = view) {
        is ComputersView.Ready -> v.computers
        is ComputersView.Failed -> v.last
        ComputersView.Loading -> emptyList()
    }
    val open = { computer: Computer, agent: Agent? ->
        graph.settings.update { it.copy(lastComputer = computer.name) }
        onOpenComputer(agent)
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        HomeList(
            login = account?.login,
            view = view,
            computers = computers,
            problem = problem,
            usage = usage,
            now = graph.clock.now(),
            actions = HomeActions(
                onHelp = onHelp,
                onAgent = { agent ->
                    val target = computers.firstOrNull { it.name == settings.lastComputer } ?: computers.firstOrNull()
                    if (target == null) needsProject = true else open(target, agent)
                },
                onNewProject = onNewProject,
                onOpenRepo = onOpenRepo,
                onUsage = onUsage,
                computer = ComputerActions(
                    onOpen = { open(it, null) },
                    onStop = { computer ->
                        scope.launch {
                            problem = runCatchingMessage { graph.computers.stop(computer.name) }
                            if (problem == null) {
                                ComputerService.disconnect(context)
                                if (graph.computerPage.computerName == computer.name) graph.computerPage.release()
                            }
                        }
                    },
                    onChrome = { Browser.open(context, it.webUrl) },
                    onDelete = { toDelete = it },
                ),
            ),
        )
    }

    toDelete?.let { computer ->
        DeleteComputerDialog(
            computer = computer,
            onDismiss = { toDelete = null },
            onConfirm = {
                toDelete = null
                scope.launch {
                    problem = runCatchingMessage { graph.computers.delete(computer.name) }
                    if (problem == null && settings.lastComputer == computer.name) {
                        graph.settings.update { it.copy(lastComputer = "") }
                        ComputerService.disconnect(context)
                        graph.computerPage.release()
                    }
                }
            },
        )
    }
    if (needsProject) {
        AlertDialog(
            onDismissRequest = { needsProject = false },
            title = { Text("First, a project") },
            text = { Text("Agents work inside a project's cloud computer. Make a new project, or open one of your repositories.") },
            confirmButton = {
                TextButton(onClick = {
                    needsProject = false
                    onNewProject()
                }) { Text("New project") }
            },
            dismissButton = {
                TextButton(onClick = {
                    needsProject = false
                    onOpenRepo()
                }) { Text("Open a repository") }
            },
        )
    }
}

/** Everything the Home list can ask for. */
private class HomeActions(
    val onHelp: () -> Unit,
    val onAgent: (Agent) -> Unit,
    val onNewProject: () -> Unit,
    val onOpenRepo: () -> Unit,
    val onUsage: () -> Unit,
    val computer: ComputerActions,
)

/** What a computer's card can ask for. */
private class ComputerActions(
    val onOpen: (Computer) -> Unit,
    val onStop: (Computer) -> Unit,
    val onChrome: (Computer) -> Unit,
    val onDelete: (Computer) -> Unit,
)

@Composable
private fun HomeList(
    login: String?,
    view: ComputersView,
    computers: List<Computer>,
    problem: String?,
    usage: CloudUsage?,
    now: Long,
    actions: HomeActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(login = login, onHelp = actions.onHelp) }
        item { SectionLabel("Agents") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Agent.entries.forEach { agent -> AgentTile(agent, Modifier.weight(1f)) { actions.onAgent(agent) } }
            }
        }
        item {
            SectionLabel("Cloud computers")
            ActionRow {
                FilledTonalButton(onClick = actions.onNewProject) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("New project")
                }
                OutlinedButton(onClick = actions.onOpenRepo) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open a repository")
                }
            }
        }
        problem?.let { item { NoticeCard(it, Tone.ERROR) } }
        if (view is ComputersView.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (view is ComputersView.Ready && computers.isEmpty()) item { EmptyComputers() }
        items(computers, key = { it.name }) { computer ->
            ComputerCard(
                computer = computer,
                now = now,
                onOpen = { actions.computer.onOpen(computer) },
                onStop = { actions.computer.onStop(computer) },
                onChrome = { actions.computer.onChrome(computer) },
                onDelete = { actions.computer.onDelete(computer) },
            )
        }
        usage?.let { item { UsageStrip(it, actions.onUsage) } }
    }
}

@Composable
private fun Header(login: String?, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark(44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("PocketIDE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(DocsContent.TAGLINE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help") }
    }
    if (login != null) {
        Spacer(Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GitHubLogo(size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Signed in as $login", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgentTile(agent: Agent, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AgentLogo(agent, size = 44.dp)
            Spacer(Modifier.padding(top = 8.dp))
            // The whole name, a little smaller if it must be, on a small phone with large text.
            val name = MaterialTheme.typography.titleSmall
            BasicText(
                agent.displayName,
                style = name.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = MIN_AGENT_NAME, maxFontSize = name.fontSize),
            )
            Text(agent.maker, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyComputers() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(
                "No cloud computer yet. Make a new project, or open one of your repositories: GitHub makes its computer in a minute or two.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ComputerCard(computer: Computer, now: Long, onOpen: () -> Unit, onStop: () -> Unit, onChrome: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(computer.repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${computer.repo.owner} · ${if (computer.repo.isPrivate) "Private" else "Public"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(computer.state.label(), computer.state.tone())
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Open in Chrome") }, onClick = {
                            menu = false
                            onChrome()
                        })
                        DropdownMenuItem(text = { Text("Delete computer…") }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            }
            computer.machineLine()?.let { Detail(it) }
            Detail(
                listOfNotNull(
                    "Used ${Formats.ago(computer.lastUsedAtMs, now)}",
                    computer.idleMinutes?.let { "stops after ${Formats.minutes(it)} idle" },
                ).joinToString(" · "),
            )
            if (computer.deletesAtMs != null && !computer.state.running) {
                Detail("GitHub deletes it ${Formats.until(computer.deletesAtMs, now)} unless you open it", Tone.WARN)
            }
            if (computer.git?.safeToDelete == false) Detail("Has code that is not on GitHub yet", Tone.WARN)
            if (!computer.setUpByPocketIde) Detail("Made outside PocketIDE: agents may need installing from Extensions", Tone.NEUTRAL)
            ActionRow(Modifier.padding(top = 6.dp)) {
                FilledTonalButton(onClick = onOpen) { Text(if (computer.state.running) "Open" else "Start and open") }
                if (computer.state.running) OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun Detail(text: String, tone: Tone? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = tone?.let { toneColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UsageStrip(usage: CloudUsage, onClick: () -> Unit) {
    val allowance = usage.allowance
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This month", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (allowance != null) {
                LinearProgressIndicator(
                    progress = { (usage.coreHoursUsed / allowance.coreHours).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${Formats.amount(usage.coreHoursUsed)} of ${allowance.coreHours} free core-hours used · " +
                        "about ${Formats.amount(usage.hoursLeftOnTwoCores ?: 0.0)} hours left on 2 cores",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(usage.unavailableReason ?: "See your hours and minutes on the Usage tab.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun DeleteComputerDialog(computer: Computer, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val unsaved = computer.git?.safeToDelete == false
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${computer.repo.name}'s computer?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "GitHub deletes the computer with everything only it holds: the agents' chats and sign-ins, and files " +
                        "not pushed to GitHub. Your repository and its code on GitHub stay.",
                )
                if (unsaved) NoticeCard("This computer has code that is not on GitHub yet. Push it first to keep it.", Tone.WARN)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = toneColor(Tone.ERROR)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Runs [block]; returns null when it worked, or the one sentence to show when it did not. */
internal suspend fun runCatchingMessage(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (e: Exception) {
    e.message ?: "Something went wrong. Try again."
}

/** The smallest a tile shrinks an agent's name to, rather than cut it short. */
private val MIN_AGENT_NAME = 11.sp
