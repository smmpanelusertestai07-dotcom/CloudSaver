package com.pocketide.ui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pocketide.AppGraph
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.manage.PlainError
import com.pocketide.vault.GitHubAppMissingException
import com.pocketide.vault.KeyState
import com.pocketide.vault.VaultKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * "Reconnect GitHub" in the main app: the same device sign-in as set-up, in a dialog. Once
 * connected, the key's halves are saved to GitHub at once rather than at the next sync.
 */
@Composable
fun ReconnectGitHubDialog(onDismiss: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val signIn = remember(graph) { DeviceSignIn.of(graph) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconnect GitHub") },
        text = {
            DialogBody {
                GitHubConnectPanel(
                    signIn = signIn,
                    openUrl = { url -> External.openUrl(context, url) },
                    onConnected = {
                        saveKeyNow(graph)
                        onDismiss()
                    },
                    startLabel = "Reconnect GitHub",
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Saves the key's halves to GitHub now, quietly: on the app's scope, so it finishes when the
 * screen is left. A failure changes nothing; the next sync tries again.
 */
fun saveKeyNow(graph: AppGraph) {
    graph.scope.launch { KeySave.of(graph.vault) }
}

/** How "Try now" for the key's halves went, in a sentence for the owner. */
internal sealed interface KeySave {
    val message: String

    data object Saved : KeySave {
        override val message = "Your chats' key is saved to GitHub."
    }

    /** [appMissing]: PocketIDE's GitHub App must be installed first; its install page is the fix. */
    data class Failed(override val message: String, val appMissing: Boolean = false) : KeySave

    companion object {
        const val NOT_SAVED = "Your chats' key is still not saved to GitHub. It is tried again at the next sync."
        const val OFFLINE = "No connection. Check the internet and try again."

        suspend fun of(vault: VaultKeys): KeySave = try {
            vault.checkKeyring()
            if (vault.state.value == KeyState.Ready) Saved else Failed(vault.notice.value ?: NOT_SAVED)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            Failed(OFFLINE)
        } catch (e: GitHubAppMissingException) {
            Failed(e.message.orEmpty(), appMissing = true)
        } catch (e: Exception) {
            Failed(PlainError.of(e))
        }
    }
}

/**
 * "Try now" on the key banner and the Safety check: [busy] while it runs, then [outcome] until
 * the owner has read it. The work runs on the app's scope, so leaving the screen does not stop it.
 */
@Stable
internal class KeySaver(private val scope: CoroutineScope, private val vault: VaultKeys) {
    var busy by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<KeySave?>(null)
        private set

    fun run() {
        if (busy) return
        busy = true
        outcome = null
        scope.launch {
            try {
                outcome = KeySave.of(vault)
            } finally {
                busy = false
            }
        }
    }

    fun dismiss() {
        outcome = null
    }
}

@Composable
internal fun rememberKeySaver(graph: AppGraph): KeySaver = remember(graph) { KeySaver(graph.scope, graph.vault) }

/** How "Try now" went, with the GitHub App's install page when that is what is missing. */
@Composable
internal fun KeySaveOutcome(saver: KeySaver, graph: AppGraph) {
    val outcome = saver.outcome ?: return
    val context = LocalContext.current
    val appMissing = (outcome as? KeySave.Failed)?.appMissing == true
    AlertDialog(
        onDismissRequest = saver::dismiss,
        title = { Text(if (outcome == KeySave.Saved) "Key saved" else "Not saved yet") },
        text = { DialogBody { Text(outcome.message) } },
        confirmButton = {
            if (appMissing) {
                TextButton(onClick = {
                    saver.dismiss()
                    External.openUrl(context, graph.gitHubAuth.installUrl())
                }) { Text("Install PocketIDE on your GitHub") }
            } else {
                TextButton(onClick = saver::dismiss) { Text("OK") }
            }
        },
        dismissButton = { if (appMissing) TextButton(onClick = saver::dismiss) { Text("Close") } },
    )
}
