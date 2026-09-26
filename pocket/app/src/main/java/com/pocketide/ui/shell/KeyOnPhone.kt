package com.pocketide.ui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pocketide.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
            GitHubConnectPanel(
                signIn = signIn,
                openUrl = { url -> External.openUrl(context, url) },
                onConnected = {
                    saveKeyNow(graph)
                    onDismiss()
                },
                startLabel = "Reconnect GitHub",
            )
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Saves the key's halves to GitHub now ("Try now"). On the app's scope: it finishes when the
 * screen is left. A failure changes nothing; the next sync tries again and the banner stays.
 */
fun saveKeyNow(graph: AppGraph) {
    graph.scope.launch {
        try {
            graph.vault.checkKeyring()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The vault's state and notice already say what is wrong.
        }
    }
}
