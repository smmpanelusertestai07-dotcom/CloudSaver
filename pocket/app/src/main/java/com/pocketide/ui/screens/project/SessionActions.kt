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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.git.Hold
import com.pocketide.model.SessionRecord
import com.pocketide.sessions.PutOnMainResult
import com.pocketide.sessions.SessionChanges
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.screens.onboarding.SetUpOffer
import kotlinx.coroutines.launch

private sealed interface PutPhase {
    data object Confirm : PutPhase
    data object Running : PutPhase
    /** The check-post holds GitHub Actions changes: the owner reads their diffs, then approves or not. */
    data class Approve(val holds: List<Hold>, val outcome: Outcome) : PutPhase
    data class Done(val outcome: Outcome) : PutPhase
}

/**
 * "Put on main" for [session]: asks first, runs the merge (check-post, push), then says what
 * happened in plain words. Conflicts and check-post findings are listed, never hidden. The merge
 * runs in the computer: when it failed because the computer is not set up, [onSetUpComputer]
 * leads there.
 */
@Composable
fun PutOnMainFlow(
    session: SessionRecord,
    onClose: () -> Unit,
    onSetUpComputer: () -> Unit,
    onOpenSession: ((String) -> Unit)? = null,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val computer by graph.computer.state.collectAsStateWithLifecycle()
    var phase by remember(session.id) { mutableStateOf<PutPhase>(PutPhase.Confirm) }
    // What would go to main, shown before the owner agrees: an agent may have changed more than it was asked to.
    val changes by produceState<Result<SessionChanges>?>(null, session.id) {
        value = attempt { graph.sessions.changes(session.id) }
    }
    when (val current = phase) {
        PutPhase.Confirm -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Put this chat on main?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This moves exactly the work of \"${session.title}\" (branch ${session.branch}) onto main, " +
                            "after the check-post, and saves it to GitHub. Afterwards the session's branch and worktree are removed.",
                    )
                    ChangeSummary(changes)
                }
            },
            confirmButton = {
                TextButton(enabled = canPutOnMain(changes), onClick = { phase = PutPhase.Running }) { Text("Put on main") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
        PutPhase.Running -> {
            LaunchedEffect(session.id) {
                val result = finish { graph.sessions.putOnMain(session.id) }
                val outcome = result.fold(
                    onSuccess = { describePutOnMain(it) },
                    onFailure = { Outcome("Not put on main", "${plainReason(it)} Nothing changed on main.", Tone.ERROR) },
                )
                val held = (result.getOrNull() as? PutOnMainResult.Blocked)?.holds.orEmpty()
                phase = if (held.any(::isApprovable)) PutPhase.Approve(held, outcome) else PutPhase.Done(outcome)
            }
            AlertDialog(
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
        }
        is PutPhase.Approve -> WorkflowApprovalDialog(
            holds = current.holds,
            approveLabel = "Approve and put on main",
            onApprove = {
                scope.launch {
                    finish { approveWorkflowChanges(graph, session.projectId, current.holds) }
                        .onSuccess { phase = PutPhase.Running }
                        .onFailure { phase = PutPhase.Done(Outcome("Not approved", plainReason(it), Tone.ERROR)) }
                }
            },
            onDismiss = { phase = PutPhase.Done(current.outcome) },
        )
        is PutPhase.Done -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(current.outcome.title, color = toneColor(current.outcome.tone)) },
            text = { Text(current.outcome.text) },
            confirmButton = {
                val open = onOpenSession
                if (current.outcome.tone == Tone.ERROR && SetUpOffer.needsOwner(computer)) {
                    TextButton(onClick = { onClose(); onSetUpComputer() }) { Text(SetUpOffer.TITLE) }
                } else if (open != null && current.outcome.tone != Tone.OK) {
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

/** The diff summary "Put on main" shows first: the totals, then the first files with their lines. */
@Composable
private fun ChangeSummary(changes: Result<SessionChanges>?) {
    when {
        changes == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reading the changes…", style = MaterialTheme.typography.bodyMedium)
        }
        changes.isFailure -> Text(
            "The changes could not be read (${plainReason(changes.exceptionOrNull() ?: IllegalStateException())}). " +
                "Look at them in the agent's screen before you go on.",
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(Tone.WARN),
        )
        else -> {
            val summary = changes.getOrThrow()
            if (summary.commits.isEmpty() && summary.files.isEmpty()) {
                Text("There is nothing to put on main: the agent has not committed anything in this session.", style = MaterialTheme.typography.bodyMedium)
                return
            }
            Text(changeTotals(summary), style = MaterialTheme.typography.titleSmall)
            summary.files.take(SUMMARY_FILES).forEach { file ->
                Text(
                    "${file.path}  +${file.added} −${file.removed}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary.files.size > SUMMARY_FILES) {
                Text("and ${WorkFormat.count(summary.files.size - SUMMARY_FILES, "more file", "more files")}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val SUMMARY_FILES = 6

/** What a session changed compared with main: its commits and files, with lines added and removed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(session: SessionRecord, onDismiss: () -> Unit, onSetUpComputer: () -> Unit) {
    val graph = rememberGraph()
    val computer by graph.computer.state.collectAsStateWithLifecycle()
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
                result.isFailure -> {
                    Text(
                        "Could not read the changes: ${plainReason(result.exceptionOrNull() ?: IllegalStateException())}",
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    // Changes are read in the computer, so setting it up is what helps.
                    if (SetUpOffer.needsOwner(computer)) {
                        TextButton(onClick = { onDismiss(); onSetUpComputer() }) { Text(SetUpOffer.TITLE) }
                    }
                }
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
