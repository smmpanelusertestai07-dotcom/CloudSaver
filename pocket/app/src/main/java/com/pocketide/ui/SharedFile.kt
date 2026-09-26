package com.pocketide.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.ui.screens.project.AddFileChoice
import com.pocketide.ui.screens.project.rememberGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Share to PocketIDE": the owner picks the session the shared file goes to, then where in it
 * (the project or Media), and that session opens. Cancelling the second question goes back to
 * the list. [onHandled] runs once the file is on its way or the owner cancels the list, so the
 * same share is never offered twice.
 */
@Composable
fun SharedFilePicker(uri: Uri, onOpenSession: (String) -> Unit, onHandled: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val all by graph.sessions.all.collectAsStateWithLifecycle()
    val sessions = remember(all) { shareTargets(all) }
    var chosen by rememberSaveable(uri) { mutableStateOf<String?>(null) }

    val sessionId = chosen
    if (sessionId != null) {
        AddFileChoice(
            sessionId = sessionId,
            uri = uri,
            scope = graph.scope,
            say = onMainThread { message -> Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show() },
            onClose = {
                onHandled()
                onOpenSession(sessionId)
            },
            onCancel = { chosen = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onHandled,
        title = { Text("Add the shared file to") },
        text = {
            if (sessions.isEmpty()) {
                Text("There is no open session yet. Start one in a project, then share the file again.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = session.id }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${session.projectId} · ${graph.agentLabel(session.agentId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onHandled) { Text("Cancel") } },
    )
}

/**
 * [show] run on the main thread: the share's result arrives on the app's background scope, and
 * a toast from a thread without a Looper throws and ends the process.
 */
internal fun onMainThread(show: (String) -> Unit): suspend (String) -> Unit = { message ->
    withContext(Dispatchers.Main) { show(message) }
}

/** Open sessions, the most recently used first: a finished or deleted one takes no new files. */
internal fun shareTargets(all: List<SessionRecord>): List<SessionRecord> =
    all.filter { it.status == SessionStatus.OPEN && it.deletedAt == null }.sortedByDescending { it.lastActivityAt }

private fun AppGraph.agentLabel(agentId: String): String =
    runCatching { agents.find(agentId)?.displayName }.getOrNull() ?: agentId
