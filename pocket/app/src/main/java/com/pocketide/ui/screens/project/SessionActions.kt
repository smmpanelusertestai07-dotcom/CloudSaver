package com.pocketide.ui.screens.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.SessionChanges
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import kotlinx.coroutines.launch

private sealed interface PutPhase {
    data object Confirm : PutPhase
    data object Running : PutPhase
    data class Done(val outcome: Outcome) : PutPhase
}

/**
 * "Put on main" for [session]: asks first, runs the merge (check-post, push), then says what
 * happened in plain words. Conflicts and check-post findings are listed, never hidden.
 */
@Composable
fun PutOnMainFlow(session: SessionRecord, onClose: () -> Unit, onOpenSession: ((String) -> Unit)? = null) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var phase by remember(session.id) { mutableStateOf<PutPhase>(PutPhase.Confirm) }
    when (val current = phase) {
        PutPhase.Confirm -> ConfirmDialog(
            title = "Put this chat on main?",
            text = "This moves exactly the work of \"${session.title}\" (branch ${session.branch}) onto main, " +
                "after the check-post, and saves it to GitHub. Afterwards the session's branch and worktree are removed.",
            confirmLabel = "Put on main",
            onConfirm = {
                phase = PutPhase.Running
                scope.launch {
                    val result = attempt { graph.sessions.putOnMain(session.id) }
                    phase = PutPhase.Done(
                        result.fold(
                            onSuccess = { describePutOnMain(it) },
                            onFailure = { Outcome("Not put on main", "${plainReason(it)} Nothing changed on main.", Tone.ERROR) },
                        ),
                    )
                }
            },
            onDismiss = { if (phase == PutPhase.Confirm) onClose() },
        )
        PutPhase.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Putting on main") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Checking, merging and saving to GitHub…")
                }
            },
            confirmButton = {},
        )
        is PutPhase.Done -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(current.outcome.title, color = toneColor(current.outcome.tone)) },
            text = { Text(current.outcome.text) },
            confirmButton = {
                val open = onOpenSession
                if (open != null && current.outcome.tone != Tone.OK) {
                    TextButton(onClick = { onClose(); open(session.id) }) { Text("Open session") }
                } else {
                    TextButton(onClick = onClose) { Text("OK") }
                }
            },
            dismissButton = if (onOpenSession != null && current.outcome.tone != Tone.OK) {
                { TextButton(onClick = onClose) { Text("Close") } }
            } else {
                null
            },
        )
    }
}

/** What a session changed compared with main: its commits and files, with lines added and removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(session: SessionRecord, onDismiss: () -> Unit) {
    val graph = rememberGraph()
    val changes by produceState<Result<SessionChanges>?>(null, session.id) {
        value = attempt { graph.sessions.changes(session.id) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Changes", style = MaterialTheme.typography.titleLarge)
            Text(
                "${session.title} · ${session.branch}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(12.dp))
            val result = changes
            when {
                result == null -> Row(Modifier.padding(vertical = 24.dp)) { CircularProgressIndicator() }
                result.isFailure -> Text(
                    "Could not read the changes: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> ChangesList(result.getOrThrow())
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChangesList(changes: SessionChanges) {
    if (changes.commits.isEmpty() && changes.files.isEmpty()) {
        Text("No changes yet. The agent has not committed anything in this session.", modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionLabel(WorkFormat.count(changes.commits.size, "commit", "commits")) }
        items(changes.commits) { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        item { SectionLabel(WorkFormat.count(changes.files.size, "file", "files")) }
        items(changes.files) { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.path,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip("+${file.added}", Tone.OK)
                Spacer(Modifier.width(4.dp))
                StatusChip("−${file.removed}", Tone.ERROR)
            }
        }
    }
}

/** Renames a session; the new title is synced with it. */
@Composable
fun RenameDialog(session: SessionRecord, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(session.id) { mutableStateOf(session.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                singleLine = true,
                label = { Text("Title") },
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onDismiss(); onDone(title.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The words shown before a chat moves to Recently deleted. */
const val DELETE_CHAT_TEXT =
    "It moves to Recently deleted. You can restore it there for 30 days; after that it is erased from your Drive for good."
