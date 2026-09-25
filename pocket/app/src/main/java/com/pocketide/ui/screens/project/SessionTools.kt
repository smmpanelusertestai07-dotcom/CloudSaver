package com.pocketide.ui.screens.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketide.model.AgentInfo
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.HandOff
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.shell.External
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The clock, ticking every [everyMs], for chips such as "sleeps in 4 min". */
@Composable
fun rememberTicker(now: () -> Long, everyMs: Long = 30_000): State<Long> = produceState(now()) {
    while (true) {
        value = now()
        delay(everyMs)
    }
}

/**
 * "Continue in <agent>": a new session in that agent's room from this session's last commit.
 * The hand-off note is shown, copied, and the new session opens when the owner is ready.
 */
@Composable
fun HandOffFlow(session: SessionRecord, to: AgentInfo, onClose: () -> Unit, onOpenSession: (String) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    var result by remember(session.id, to.id) { mutableStateOf<Result<HandOff>?>(null) }
    LaunchedEffect(session.id, to.id) { result = finish { graph.sessions.handOff(session.id, to.id) } }
    val takesPrompts = remember(to.id) { runCatching { graph.rooms.takesPrompts(to.id) }.getOrDefault(false) }
    val handOff = result?.getOrNull()
    AlertDialog(
        onDismissRequest = { if (result != null) onClose() },
        title = { Text("Continue in ${to.displayName}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    result == null -> Text("Making a new session from this one's last commit…")
                    handOff == null -> Text(
                        "Could not hand it over: ${plainReason(result?.exceptionOrNull() ?: IllegalStateException())}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        val text = if (takesPrompts) HAND_OFF_PROMPT_TEXT else HAND_OFF_TEXT
                        Text(text.format(to.displayName), style = MaterialTheme.typography.bodyMedium)
                        SelectableText(handOff.note, Modifier.fillMaxWidth(), sizeSp = 13f)
                    }
                }
            }
        },
        confirmButton = {
            if (handOff != null) {
                if (takesPrompts) {
                    TextButton(onClick = {
                        // The room starts detached from this dialog; the agent screen then waits on the same room.
                        graph.scope.launch { runCatching { graph.rooms.open(to.id, handOff.session.id, handOff.note) } }
                        onClose()
                        onOpenSession(handOff.session.id)
                    }) { Text("Open with the note") }
                } else {
                    TextButton(onClick = {
                        copyText(context, "Hand-off note", handOff.note)
                        onClose()
                        onOpenSession(handOff.session.id)
                    }) { Text("Copy note and open") }
                }
            }
        },
        dismissButton = { TextButton(enabled = result != null, onClick = onClose) { Text("Close") } },
    )
}

private const val HAND_OFF_PROMPT_TEXT =
    "The new session is ready; this one stays as it is. %s opens with this note in its message box, ready to send."

private const val HAND_OFF_TEXT =
    "The new session is ready; this one stays as it is. Give %s this note as your first message: it is copied when you open the session."

private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Renames the session's branch; only a branch not yet on GitHub can be renamed. [scope] outlives
 * the dialog, which closes before the rename runs.
 */
@Composable
fun RenameBranchDialog(session: SessionRecord, snackbar: SnackbarHostState, scope: CoroutineScope, onDismiss: () -> Unit) {
    val graph = rememberGraph()
    val prefix = BranchName.prefix(session.branch)
    var name by remember(session.id) { mutableStateOf(BranchName.tail(session.branch)) }
    val clean = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename branch") },
        text = {
            DialogBody(spacing = 8.dp) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(BranchName.MAX_LENGTH) },
                    singleLine = true,
                    label = { Text("Name") },
                    prefix = { Text(prefix, fontFamily = FontFamily.Monospace) },
                )
                Text(
                    "Only a branch that is not on GitHub yet can be renamed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = clean.isNotEmpty(), onClick = {
                onDismiss()
                scope.act(snackbar, "Could not rename the branch", done = "Branch renamed.") { graph.sessions.renameBranch(session.id, clean) }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Where a picked file goes: into the project, for the agent to work on, or to Media as an attachment. */
@Composable
fun AddFileFlow(sessionId: String, snackbar: SnackbarHostState, scope: CoroutineScope, onClose: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Uri?>(null) }
    var launched by remember { mutableStateOf(false) }
    // The system file picker: no storage permission, and only the file the owner picks.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onClose() else picked = uri
    }
    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            External.leaving(context)
            if (runCatching { pick.launch(arrayOf("*/*")) }.isFailure) {
                snackbar.showSnackbar("This phone has no file picker.")
                onClose()
            }
        }
    }
    val uri = picked ?: return
    AddFileChoice(sessionId, uri, scope, say = { snackbar.showSnackbar(it) }, onClose = onClose)
}

/**
 * Asks where [uri] goes in session [sessionId] and adds it there. [scope] outlives the dialog,
 * which closes before the file is copied; [say] tells the owner how it went.
 */
@Composable
fun AddFileChoice(sessionId: String, uri: Uri, scope: CoroutineScope, say: suspend (String) -> Unit, onClose: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add the file where?") },
        text = { Text(ADD_FILE_TEXT) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                scope.launch {
                    finish {
                        val name = withContext(Dispatchers.IO) { displayName(context, uri) }
                        val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) }
                            ?: throw IllegalStateException("The file could not be opened.")
                        graph.sessions.addFile(sessionId, name, input, intoProject = true)
                    }
                        .onSuccess { say("Added. The agent finds it at ${it.guestPath}.") }
                        .onFailure { say("Could not add the file: ${plainReason(it)}") }
                }
            }) { Text("Into the project") }
        },
        dismissButton = {
            TextButton(onClick = {
                onClose()
                scope.launch {
                    finish { graph.media.addFromPhone(sessionId, uri) }
                        .onSuccess { say("Added ${it.name} to this session's Media.") }
                        .onFailure { say("Could not add the file: ${plainReason(it)}") }
                }
            }) { Text("To Media") }
        },
    )
}

private const val ADD_FILE_TEXT =
    "Into the project: the file goes into this session's folder, for the agent to use and commit. " +
        "To Media: it is kept with the chat as an attachment."

private fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "file"

/**
 * The part of a session's branch name the owner may choose (`pocket/<agent>/<date>-<name>`);
 * the sessions module makes the chosen words safe for a branch name.
 */
object BranchName {
    const val MAX_LENGTH = 60
    private val DATED = Regex("^(.*/\\d{4}-\\d{2}-\\d{2}-)")

    fun prefix(branch: String): String =
        DATED.find(branch)?.groupValues?.get(1) ?: branch.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    fun tail(branch: String): String = branch.removePrefix(prefix(branch))
}
