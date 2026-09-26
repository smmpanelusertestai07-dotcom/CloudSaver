package com.pocketide.ui.screens.data

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.cloud.Computer
import com.pocketide.cloud.ComputerService
import com.pocketide.cloud.ComputersView
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.screens.home.DeleteComputerDialog
import com.pocketide.ui.screens.home.runCatchingMessage
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.web.Browser
import kotlinx.coroutines.launch

/**
 * Where each piece of the owner's data lives, who can see it, and how to delete it; and leaving
 * PocketIDE, which deletes the phone's part and leaves the owner's GitHub account as it is.
 */
@Composable
fun YourDataScreen(onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val view by graph.computers.view.collectAsStateWithLifecycle()
    val computers = (view as? ComputersView.Ready)?.computers ?: (view as? ComputersView.Failed)?.last.orEmpty()
    var toDelete by remember { mutableStateOf<Computer?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Your data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "PocketIDE has no server and no database. Your data is in your GitHub account and with the AI companies you sign in to; " +
                "this phone keeps only your sign-in and settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        problem?.let { NoticeCard(it, Tone.ERROR) }

        Place(
            "On this phone",
            "Your GitHub sign-in (encrypted with a key only this phone has), the computer page's GitHub sign-in, and your settings. " +
                "No code, no chats, no files. Uninstalling PocketIDE deletes them.",
        ) {
            OutlinedButton(onClick = { clearing = true }) { Text("Delete from this phone") }
        }
        Place(
            "Your code",
            "In your GitHub repositories. New projects are private: only you, and people you invite, can see them.",
        ) {
            OutlinedButton(onClick = { Browser.open(context, "https://github.com/settings/repositories") }) { Text("Your repositories") }
        }
        Place(
            "Cloud computers",
            "One GitHub Codespace per project, each its own private machine that only you can open. GitHub deletes one that stays " +
                "unused for the days you chose in Settings.",
        ) {
            computers.forEach { computer ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(computer.repo.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { toDelete = computer }) { Text("Delete", color = toneColor(Tone.ERROR)) }
                }
            }
            OutlinedButton(onClick = { Browser.open(context, "https://github.com/codespaces") }) { Text("All on GitHub") }
        }
        Place(
            "Chats and agent sign-ins",
            "Inside each project's cloud computer, kept by each agent itself: " +
                Agent.entries.joinToString("; ") { "${it.displayName} in ${it.chatsFolder}" } +
                ". Delete one chat in the agent's own history, or all of them by deleting the computer. They are not on this phone, " +
                "so uninstalling PocketIDE does not delete them.",
        )
        Place(
            "At the AI companies",
            "What you ask an agent, and the code it reads, goes to its company under your account there. Their own policies say " +
                "how long they keep it and whether it trains their models.",
        ) {
            ActionRow {
                Agent.entries.forEach { agent ->
                    OutlinedButton(onClick = { Browser.open(context, agent.privacyUrl) }) { Text(agent.maker) }
                }
            }
        }
        Place(
            "Builds",
            "GitHub Actions keeps build logs and files for the days your repository sets (90 by default), visible to whoever can see the repository.",
        )

        SectionCard("Leave PocketIDE") {
            Text(
                "Deletes everything PocketIDE keeps on this phone, then opens Android's page for PocketIDE so you can uninstall it. " +
                    "Your repositories and cloud computers stay in your GitHub account; nothing there is deleted.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { Browser.open(context, graph.gitHubAuth.authorizationsUrl()) }) {
                Text("First, remove PocketIDE's access on GitHub (optional)")
            }
            PrimaryAction("Leave PocketIDE", onClick = { leaving = true })
            TextButton(onClick = { onHelpPage(DocsContent.YOUR_DATA_ID) }) { Text("More about your data") }
        }
    }

    toDelete?.let { computer ->
        DeleteComputerDialog(
            computer = computer,
            onDismiss = { toDelete = null },
            onConfirm = {
                toDelete = null
                scope.launch {
                    problem = runCatchingMessage { graph.computers.delete(computer.name) }
                    if (problem == null && graph.settings.settings.value.lastComputer == computer.name) {
                        graph.settings.update { it.copy(lastComputer = "") }
                        ComputerService.disconnect(context)
                        graph.computerPage.release()
                    }
                }
            },
        )
    }
    if (clearing || leaving) {
        AlertDialog(
            onDismissRequest = {
                clearing = false
                leaving = false
            },
            title = { Text(if (leaving) "Leave PocketIDE?" else "Delete PocketIDE's data from this phone?") },
            text = {
                Text(
                    "PocketIDE forgets your GitHub sign-in, the computer page's sign-in and your settings on this phone. " +
                        "Your code, cloud computers and chats stay in your GitHub account." +
                        if (leaving) " Then Android's page for PocketIDE opens: tap Uninstall there." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uninstall = leaving
                    clearing = false
                    leaving = false
                    scope.launch {
                        deleteFromPhone(context, graph)
                        if (uninstall) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) { Text("Delete", color = toneColor(Tone.ERROR)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    clearing = false
                    leaving = false
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Place(title: String, text: String, actions: (@Composable () -> Unit)? = null) {
    SectionCard(title) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        actions?.let { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { it() } }
    }
}
