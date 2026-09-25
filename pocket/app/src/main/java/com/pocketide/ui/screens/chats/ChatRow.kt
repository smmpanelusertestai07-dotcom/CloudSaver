package com.pocketide.ui.screens.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.core.Ist
import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus
import com.pocketide.sync.SessionBackup
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.project.ConfirmDialog
import com.pocketide.ui.screens.project.DELETE_CHAT_TEXT
import com.pocketide.ui.screens.project.PutOnMainFlow
import com.pocketide.ui.screens.project.RenameDialog
import com.pocketide.ui.screens.project.WorkFormat
import com.pocketide.ui.screens.project.act
import com.pocketide.ui.screens.project.rememberGraph
import com.pocketide.ui.screens.project.sessionStatusLabel
import kotlinx.coroutines.CoroutineScope

/** The questions a chat's menu can ask; held by the screen, so they outlive the row. */
internal enum class ChatDialog { RENAME, PUT_ON_MAIN, REMOVE_MEDIA, DELETE }

/** One chat in the list. Actions that need a question go to [onDialog]; the rest run here. */
@Composable
internal fun ChatRow(
    session: SessionRecord,
    agentLabel: String,
    running: Boolean,
    backup: SessionBackup?,
    nav: PocketNav,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    onDialog: (ChatDialog) -> Unit,
) {
    val graph = rememberGraph()
    var menu by remember { mutableStateOf(false) }
    val (status, tone) = sessionStatusLabel(session.status, running)
    val (backupLabel, backupTone) = backupState(session, backup)

    Card(
        onClick = { nav.transcript(session.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusChip(status, tone)
                }
                Text(
                    "$agentLabel · ${session.projectId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Ist.dateTime(session.lastActivityAt)} · ${WorkFormat.bytes(sessionBytes(session))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(backupLabel, backupTone)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Read") }, onClick = { menu = false; nav.transcript(session.id) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onDialog(ChatDialog.RENAME) })
                    if (session.canContinue()) {
                        DropdownMenuItem(text = { Text("Continue") }, onClick = {
                            menu = false
                            scope.act(snackbar, "Could not continue", then = { nav.agent(session.id) }) {
                                graph.sessions.continueSession(session.id)
                            }
                        })
                    }
                    if (session.status == SessionStatus.OPEN || session.status == SessionStatus.CONFLICT_COPY) {
                        DropdownMenuItem(text = { Text("Put on main") }, onClick = { menu = false; onDialog(ChatDialog.PUT_ON_MAIN) })
                    }
                    DropdownMenuItem(
                        text = { Text("Don't back up this chat") },
                        trailingIcon = { Checkbox(checked = !session.backUp, onCheckedChange = null) },
                        onClick = {
                            menu = false
                            val backUp = !session.backUp
                            scope.act(
                                snackbar,
                                "Could not change it",
                                done = if (backUp) "This chat will be backed up to your Drive." else "Kept on this phone only. Not backed up.",
                            ) { graph.sessions.setBackUp(session.id, backUp) }
                        },
                    )
                    if (session.mediaCount > 0) {
                        DropdownMenuItem(text = { Text("Remove media") }, onClick = { menu = false; onDialog(ChatDialog.REMOVE_MEDIA) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDialog(ChatDialog.DELETE) })
                }
            }
        }
    }
}

/** The question [dialog] asks about [session]. */
@Composable
internal fun ChatDialogHost(
    dialog: ChatDialog,
    session: SessionRecord,
    nav: PocketNav,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val graph = rememberGraph()
    when (dialog) {
        ChatDialog.RENAME -> RenameDialog(
            session,
            onDone = { title -> scope.act(snackbar, "Could not rename") { graph.sessions.rename(session.id, title) } },
            onDismiss = onClose,
        )
        ChatDialog.PUT_ON_MAIN -> PutOnMainFlow(session, onClose = onClose, onOpenSession = nav::agent)
        ChatDialog.REMOVE_MEDIA -> ConfirmDialog(
            title = "Remove media, keep the chat?",
            text = "The ${WorkFormat.count(session.mediaCount, "file", "files")} in this chat's Media (${WorkFormat.bytes(session.mediaBytes)}) " +
                "are removed from this phone and your Drive. The messages stay.",
            confirmLabel = "Remove media",
            destructive = true,
            onConfirm = { scope.act(snackbar, "Could not remove media", done = "Media removed.") { graph.sessions.removeMedia(session.id) } },
            onDismiss = onClose,
        )
        ChatDialog.DELETE -> ConfirmDialog(
            title = "Delete \"${session.title}\"?",
            text = DELETE_CHAT_TEXT,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { scope.act(snackbar, "Could not delete", done = "Moved to Recently deleted.") { graph.sessions.delete(session.id) } },
            onDismiss = onClose,
        )
    }
}
