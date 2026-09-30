package com.pocketide.ui.screens.cloudshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.cloudshell.CloudShell
import com.pocketide.cloudshell.IdePlace
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.Browser
import com.pocketide.ui.web.IdeTab

/**
 * The computer: Google Cloud Shell. Its account and state, its free limits, where its data is (and
 * where it is not), how to see, download and delete it, and what keeps the Google account safe.
 */
@Composable
fun CloudShellScreen(onHelp: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val account = settings.cloudAccount
    val unused = CloudShell.daysUnused(settings, graph.clock.now())
    val pick = rememberAccountPicker { name -> graph.settings.update { it.copy(cloudAccount = name) } }
    var again by remember { mutableStateOf(false) }
    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "Cloud Shell",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help") }
        }
        Text("Google's Linux computer, where your agents work", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Your computer") {
                Text(
                    "Account: $account\nLast opened from PocketIDE: " +
                        (if (unused == 0L) "today" else "$unused days ago") +
                        ". Google deletes the home folder after ${CloudShell.DELETED_AFTER_DAYS} days without use; PocketIDE " +
                        "asks for the set-up again before that.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActionRow {
                    PrimaryAction("Start Cloud Shell", onClick = { IdeTab.open(context, IdePlace.TERMINAL) })
                    OutlinedButton(onClick = pick) { Text("Change account") }
                    OutlinedButton(onClick = { again = true }) { Text("Run the set-up again") }
                }
            }

            SectionCard("Each agent, its own VS Code") {
                Text(
                    Agent.entries.joinToString("\n") { "${it.displayName}: port ${CloudShell.port(it)}, projects in ${CloudShell.projects(it)}" } +
                        "\nEach has its own settings and extensions. Add any extension from its Extensions view (Open VSX); " +
                        "they update by themselves, and the agents are checked for updates once a day.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard("Free limits") {
                Text(
                    "50 hours a week (about 7 hours a day), at most 12 hours in one session. Cloud Shell stops about 40 minutes " +
                        "after you stop using it, so agents do not work on while you are away. 5 GB home folder; the set-up uses " +
                        "about 1.6 GB. Your hours: in Cloud Shell, Session information > Usage quota.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { Browser.open(context, CloudShell.LIMITS) }) { Text("Google's limits") }
            }

            SectionCard("Where your data is") {
                Text(
                    "Only in your Cloud Shell home folder, which only your Google account opens: each agent's projects, its " +
                        "chats and sign-in (~/.claude, ~/.codex, ~/.gemini) and its VS Code (~/.pocketide). What you ask an agent, " +
                        "and the code it reads, also goes to its company. It is not in Drive, Photos or your Google Cloud " +
                        "projects, and agent chats do not show on claude.ai or chatgpt.com.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Tidied by itself when Cloud Shell starts: caches unused for 14 days, logs after 7 days, Codex chats after " +
                        "30 days (Claude Code deletes its own after 30 days). Projects are never deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ActionRow {
                    OutlinedButton(onClick = { IdeTab.open(context, IdePlace.FILES) }) { Text("See the files") }
                    OutlinedButton(onClick = { openCloudShell(context, CloudShell.console(account)) }) { Text("Cloud console") }
                    OutlinedButton(onClick = { Browser.open(context, CloudShell.MOBILE_APP) }) { Text("Google Cloud app") }
                }
                TextButton(onClick = { Browser.open(context, CloudShell.FILES) }) { Text("Download or upload files") }
            }

            SectionCard("Delete") {
                Text(
                    "A file or project: delete it in VS Code. Everything: in Cloud Shell run sudo rm -rf \$HOME, then More > " +
                        "Restart; Cloud Shell starts again empty and PocketIDE asks for the set-up again. Agent sign-ins: sign " +
                        "out in each agent, then remove the access in your Claude, ChatGPT and Google account settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { Browser.open(context, CloudShell.RESET) }) { Text("Google's reset steps") }
            }

            NoticeCard(
                "Use Cloud Shell yourself, while you work, as Google intends. No miners, scanners or tricks to keep it awake, " +
                    "and never share a Web Preview link: breaking Google's rules can turn Cloud Shell off for your account.",
                tone = Tone.WARN,
                title = "Keep your Google account safe",
            )
            ActionRow {
                TextButton(onClick = { Browser.open(context, CloudShell.TERMS) }) { Text("Google Cloud terms") }
                TextButton(onClick = { Browser.open(context, CloudShell.PRIVACY) }) { Text("Google Cloud privacy") }
                TextButton(onClick = { onHelpPage(DocsContent.COMPUTER_ID) }) { Text("More in Help") }
            }
        }
    }
    if (again) {
        AlertDialog(
            onDismissRequest = { again = false },
            title = { Text("Run the set-up again?") },
            text = {
                DialogBody {
                    Text(
                        "For when Cloud Shell was reset or deleted, or an agent is missing. PocketIDE shows the set-up; paste its " +
                            "command in Cloud Shell again. It only adds what is missing: your projects and chats stay.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    again = false
                    graph.settings.update { it.copy(cloudSetUpAt = 0) }
                }) { Text("Set up again") }
            },
            dismissButton = { TextButton(onClick = { again = false }) { Text("Cancel") } },
        )
    }
}
