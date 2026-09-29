package com.pocketide.ui.screens.data

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
import androidx.compose.runtime.LaunchedEffect
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
import com.pocketide.agents.Agent
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.linux.ComputerInfo
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.web.Browser
import kotlinx.coroutines.launch

/**
 * Where each piece of the owner's data lives, who can see it, how big it is, and how to delete it.
 * Everything is on this phone, inside PocketIDE; only what an agent sends to its own company leaves.
 */
@Composable
fun YourDataScreen(onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ComputerInfo?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { info = runCatching { graph.computer.info() }.getOrNull() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Your data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "PocketIDE has no server and no database of its own. Everything is on this phone, in PocketIDE's private storage, " +
                "which Android lets no other app read. Nothing is written to the phone's shared storage.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Place(
            "Your projects",
            "In ~/projects on the computer" + (info?.let { " (with sign-ins and chats: ${Formats.size(it.homeBytes)})" } ?: "") +
                ". To keep a copy elsewhere, push it to GitHub with git.",
        )
        Place(
            "Chats and agent sign-ins",
            "Kept by each agent itself, on the computer: " + Agent.entries.joinToString("; ") { "${it.displayName} in ${it.chatsFolder}" } +
                ". Delete one chat in the agent's own history. Resetting Ubuntu keeps them; deleting the computer deletes them.",
        )
        Place(
            "Ubuntu and its programs",
            "The computer's system" + (info?.let { " (${Formats.size(it.systemBytes)})" } ?: "") +
                ", rebuilt at any time from the published downloads with Reset.",
        )
        Place(
            "Keys and settings",
            "Your keys are sealed with a key in the phone's secure hardware; settings are plain. Android's own backup does not copy either.",
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

        SectionCard("Delete everything") {
            Text(
                "Deletes the computer with your projects, the agents with their sign-ins and chats, your keys and your settings. " +
                    "Uninstalling PocketIDE does the same.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryAction("Delete everything", onClick = { deleting = true })
            TextButton(onClick = { onHelpPage(DocsContent.YOUR_DATA_ID) }) { Text("More about your data") }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete everything?") },
            text = {
                DialogBody {
                    Text(
                        "Your projects, chats, sign-ins, keys and settings are deleted from this phone. This cannot be undone. " +
                            "Push anything you want to keep to GitHub first.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { deleteEverything(context, graph) }
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Place(title: String, text: String, actions: @Composable () -> Unit = {}) {
    SectionCard(title) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}
