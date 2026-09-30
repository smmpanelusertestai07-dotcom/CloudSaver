package com.pocketide.ui.screens.data

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pocketide.cloudshell.CloudShell
import com.pocketide.core.AppFolders
import com.pocketide.core.OldComputer
import com.pocketide.docs.AppPlace
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.components.ActionRow
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.Formats
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.screens.help.LocalOpenPlace
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.web.Browser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class Ask { DELETE_OLD, DELETE_PHONE }

/**
 * Where each piece of the owner's data lives, who can see it, and how to delete it. The work is in
 * the owner's Cloud Shell; the phone keeps only PocketIDE's settings, and, after an update from
 * version 5, that version's computer until the owner saves what they want from it and deletes it.
 */
@Composable
fun YourDataScreen(onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val openPlace = LocalOpenPlace.current
    val old = remember { OldComputer(AppFolders.of(context).oldComputer) }
    var oldThere by remember { mutableStateOf(old.exists()) }
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Ask?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Your data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "PocketIDE has no server and no database of its own. Your work is in your own Google Cloud Shell; this phone " +
                "keeps only PocketIDE's settings.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Place(
            "In your Cloud Shell",
            "Your projects (~/projects), each agent's chats and sign-in (~/.claude, ~/.codex, ~/.gemini) and each agent's VS " +
                "Code (~/.pocketide). Only your Google account opens it. Google deletes it after " +
                "${CloudShell.DELETED_AFTER_DAYS} days without use.",
        ) {
            OutlinedButton(onClick = { openPlace(AppPlace.COMPUTER.id) }) { Text("See it, download it, delete it") }
        }
        Place(
            "On this phone",
            "PocketIDE's settings: the theme, App lock, the Google account Cloud Shell opens with, and when it was set up and " +
                "last opened. Android's own backup does not copy them.",
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
        if (oldThere) OldComputerCard(old, busy = busy, onBusy = { busy = it }, onDelete = { asking = Ask.DELETE_OLD })
        SectionCard("Delete PocketIDE's data on this phone") {
            Text(
                "Deletes PocketIDE's settings and files on this phone; the app starts again from the welcome. Your Cloud Shell " +
                    "stays as it is: delete it there (Computer > Delete). Uninstalling PocketIDE does the same.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PrimaryAction("Delete from this phone", onClick = { asking = Ask.DELETE_PHONE }, enabled = !busy)
            TextButton(onClick = { onHelpPage(DocsContent.YOUR_DATA_ID) }) { Text("More about your data") }
        }
    }

    asking?.let { ask ->
        DeleteDialog(ask, withOld = oldThere, onDismiss = { asking = null }) {
            asking = null
            scope.launch {
                if (ask == Ask.DELETE_OLD) {
                    withContext(Dispatchers.IO) { old.delete() }
                    oldThere = old.exists()
                    if (oldThere) Toast.makeText(context, "Part of it could not be deleted; try again.", Toast.LENGTH_LONG).show()
                } else {
                    deletePhoneData(context, graph)
                }
            }
        }
    }
}

/** PocketIDE 5's computer: its size, a zip of its projects and chats, and deleting it. */
@Composable
private fun OldComputerCard(old: OldComputer, busy: Boolean, onBusy: (Boolean) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(old) { size = withContext(Dispatchers.IO) { old.size() } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP)) { uri ->
        if (uri != null) {
            scope.launch {
                onBusy(true)
                val saved = withContext(Dispatchers.IO) { saveZip(context, old, uri) }
                onBusy(false)
                val said = when (saved) {
                    null -> "Could not save the zip there. Try another folder."
                    0 -> "There were no projects or chats to save."
                    else -> "Saved $saved files."
                }
                Toast.makeText(context, said, Toast.LENGTH_LONG).show()
            }
        }
    }
    SectionCard("PocketIDE 5's phone computer") {
        Text(
            "Version 5 kept a Linux computer on this phone" + (size?.let { " (${Formats.size(it)})" } ?: "") +
                ". Version 6 works in Cloud Shell and cannot open it. Save its projects and the agents' chats as a zip " +
                "(sign-ins are left out), upload the zip to Cloud Shell if you like (its ⋮ menu > Upload), then delete it " +
                "to free the space.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ActionRow {
            PrimaryAction(if (busy) "Saving…" else "Save as a zip", onClick = { save.launch(ZIP_NAME) }, enabled = !busy, busy = busy)
            OutlinedButton(onClick = onDelete, enabled = !busy) { Text("Delete it") }
        }
    }
}

/** How many files the zip at [uri] holds, or null when it could not be written there. */
private fun saveZip(context: Context, old: OldComputer, uri: Uri): Int? = try {
    context.contentResolver.openOutputStream(uri)?.let(old::saveTo)
} catch (e: IOException) {
    null
} catch (e: SecurityException) {
    null
}

@Composable
private fun DeleteDialog(ask: Ask, withOld: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (ask == Ask.DELETE_OLD) "Delete the phone computer?" else "Delete PocketIDE's data?") },
        text = {
            DialogBody {
                Text(
                    if (ask == Ask.DELETE_OLD) {
                        "Its projects, chats and sign-ins are deleted from this phone. This cannot be undone: save the zip first."
                    } else {
                        "PocketIDE's settings and files on this phone are deleted" +
                            (if (withOld) ", PocketIDE 5's phone computer with them" else "") +
                            ". Your Cloud Shell is not touched."
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Place(title: String, text: String, actions: @Composable () -> Unit = {}) {
    SectionCard(title) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        actions()
    }
}

private const val ZIP = "application/zip"
private const val ZIP_NAME = "pocketide-phone-projects.zip"
