package com.pocketide.ui.screens.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketide.cloudshell.CloudShell
import com.pocketide.cloudshell.IdePlace
import com.pocketide.core.AppFolders
import com.pocketide.core.OldComputer
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.screens.cloudshell.SignInHelpDialog
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.IdeTab

/**
 * Home: the computer, Google Cloud Shell, with its Start button; the three agents, each opening its
 * own VS Code there; and Cloud Shell's terminal and files. Everything opens in a Chrome tab dressed
 * as PocketIDE's, with the tools button and the other agents in its menu.
 */
@Composable
fun HomeScreen(onComputer: () -> Unit, onYourData: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    val settings by context.graph.settings.settings.collectAsStateWithLifecycle()
    var signIn by remember { mutableStateOf<Agent?>(null) }
    var extensions by remember { mutableStateOf(false) }
    val oldComputer = remember { OldComputer(AppFolders.of(context).oldComputer).exists() }
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
        if (CloudShell.newerSetUp(settings)) {
            NoticeCard(
                "This version of PocketIDE brings a newer Cloud Shell set-up. Run it once more: one paste, and it only " +
                    "adds what changed. Your projects and chats stay.",
                title = "Update your Cloud Shell set-up",
            )
            TextButton(onClick = { context.graph.settings.update { it.copy(cloudSetUpAt = 0) } }) { Text("Update the set-up") }
            Gap(8.dp)
        }
        if (oldComputer) {
            NoticeCard(
                "PocketIDE 5's phone computer is still on this phone, with its projects. Save them as a zip, then delete it " +
                    "to free the space.",
                title = "Your old phone computer",
            )
            TextButton(onClick = onYourData) { Text("Open Your data") }
            Gap(8.dp)
        }
        OutlinedCard {
            ListRow(
                leading = { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) },
                title = "Google Cloud Shell",
                subtitle = settings.cloudAccount,
                onClick = onComputer,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                Text(
                    "It stops about 40 minutes after you leave it. After a break, start it first: the agents' VS Code starts " +
                        "with it, in about a minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12.dp)
                PrimaryAction("Start Cloud Shell", onClick = { IdeTab.open(context, IdePlace.TERMINAL) })
            }
        }
        SectionLabel("Agents")
        OutlinedCard {
            Agent.entries.forEach { agent ->
                AgentRow(agent, onOpen = { IdeTab.open(context, IdePlace.of(agent)) }, onSignIn = { signIn = agent })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            ListRow(
                leading = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Extensions",
                subtitle = "Add any in an agent's VS Code; they update by themselves",
                onClick = { extensions = true },
            )
        }
        SectionLabel("Tools")
        OutlinedCard {
            ListRow(
                leading = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Terminal",
                subtitle = "Cloud Shell's command line",
                onClick = { IdeTab.open(context, IdePlace.TERMINAL) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                leading = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(28.dp)) },
                title = "Files",
                subtitle = "Your Cloud Shell home folder, in Cloud Shell's editor",
                onClick = { IdeTab.open(context, IdePlace.FILES) },
            )
        }
        Gap(12.dp)
        FinePrint(
            "Each opens in Chrome with your Google account. There, the arrow returns here, the tools button shows every " +
                "agent, the terminal and the files, and Chrome's menu (⋮) switches between them.",
        )
    }
    signIn?.let { agent -> SignInHelpDialog(agent) { signIn = null } }
    if (extensions) ExtensionsDialog { extensions = false }
}

/** An agent: its logo and maker; a tap opens its VS Code, the key how it signs in. */
@Composable
private fun AgentRow(agent: Agent, onOpen: () -> Unit, onSignIn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClickLabel = "Open ${agent.displayName}", onClick = onOpen)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentLogo(agent, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(agent.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${agent.maker} · ${CloudShell.projects(agent)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onSignIn) { Icon(Icons.Outlined.Key, contentDescription = "How to sign in to ${agent.displayName}") }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun ExtensionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extensions") },
        text = {
            DialogBody {
                Text(
                    "Each agent's VS Code keeps its own extensions. To add one, open the agent, tap Extensions (the four " +
                        "squares) in VS Code's side bar, search, and tap Install.",
                )
                Text(
                    "They come from Open VSX and update by themselves; PocketIDE checks the agents for updates every day. " +
                        "Install only what you trust: an extension can use everything in your Cloud Shell.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
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
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
