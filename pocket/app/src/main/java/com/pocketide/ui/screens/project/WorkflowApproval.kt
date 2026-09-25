package com.pocketide.ui.screens.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.AppGraph
import com.pocketide.git.Hold
import com.pocketide.git.HoldKind

/**
 * The check-post's holds, for the owner to read: each changed GitHub Actions file with its diff,
 * and an Approve that lets exactly that content through. A build output has no approval; its
 * reason says the agent must remove it. The agent can never approve: only this dialog does.
 */
@Composable
fun WorkflowApprovalDialog(holds: List<Hold>, approveLabel: String, onApprove: () -> Unit, onDismiss: () -> Unit) {
    val approvable = holds.any(::isApprovable)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (approvable) "Approve the GitHub Actions change?" else "The check-post held the push") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (approvable) Text(APPROVAL_TEXT, style = MaterialTheme.typography.bodyMedium)
                holds.forEach { hold -> HoldCard(hold) }
            }
        },
        confirmButton = {
            if (approvable) TextButton(onClick = onApprove) { Text(approveLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (approvable) "Not now" else "Close") } },
    )
}

@Composable
private fun HoldCard(hold: Hold) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(hold.path, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        if (hold.detail.isNotBlank()) Text(hold.detail, style = MaterialTheme.typography.bodyMedium)
        if (hold.kind == HoldKind.WORKFLOW_CHANGE && hold.diff.isNotBlank()) {
            SelectionContainer {
                Text(
                    hold.diff,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        }
    }
}

internal fun isApprovable(hold: Hold): Boolean = hold.kind == HoldKind.WORKFLOW_CHANGE && hold.approvalKey != null

/** Records the owner's approval of every held workflow change in [holds], for [projectId]'s check-post. */
suspend fun approveWorkflowChanges(graph: AppGraph, projectId: String, holds: List<Hold>) {
    val bare = graph.dirs.bareRepo(projectId)
    holds.filter(::isApprovable).forEach { hold -> graph.git.approveWorkflowChange(bare, checkNotNull(hold.approvalKey)) }
}

private const val APPROVAL_TEXT =
    "The agent changed GitHub Actions code. Workflows run on GitHub with this repository's permissions and " +
        "Secrets, so read each change before you approve it. Only exactly what is shown is let through."
