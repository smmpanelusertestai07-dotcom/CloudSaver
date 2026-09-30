package com.pocketide.ui.screens.cloudshell

import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketide.agents.Agent
import com.pocketide.cloudshell.CloudShell
import com.pocketide.graph
import com.pocketide.ui.web.Browser

/** Opens a Cloud Shell page in a Chrome tab, and notes when PocketIDE last opened Cloud Shell. */
fun openCloudShell(context: Context, url: String) {
    val graph = context.graph
    graph.settings.update { it.copy(cloudOpenedAt = graph.clock.now()) }
    Browser.open(context, url)
}

fun copyText(context: Context, text: String, label: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/**
 * Android's own account chooser for Google accounts, with Add account in it: nothing opens with an
 * account by itself, and PocketIDE sees only the one account picked (no contacts permission).
 */
@Composable
fun rememberAccountPicker(onPicked: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !name.isNullOrBlank()) onPicked(name)
    }
    return { launcher.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf(GOOGLE), null, null, null, null)) }
}

private const val GOOGLE = "com.google"

/** A command shown as it will be pasted, selectable. */
@Composable
fun CommandBox(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
        }
    }
}

/** How [agent] signs in inside its Cloud Shell VS Code. */
fun signInSteps(agent: Agent): String = when (agent) {
    Agent.CLAUDE ->
        "Open Claude Code in its VS Code and tap Sign in. Chrome opens: sign in, copy the code the page shows, come " +
            "back and paste it where Claude Code asks."
    Agent.CODEX ->
        "Codex's own Sign in with ChatGPT expects the computer to be this phone. In Cloud Shell, use device code " +
            "sign-in: turn it on in ChatGPT (Settings > Security), then in Codex's VS Code open the Terminal and run " +
            "codex login --device-auth; open the link it shows and enter the code."
    Agent.ANTIGRAVITY ->
        "Open Antigravity in its VS Code and sign in with Google. If the page ends at localhost, open the Terminal, " +
            "run agy, open the link it shows, sign in and paste the code back."
}

@Composable
fun SignInHelpDialog(agent: Agent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to ${agent.displayName}") },
        text = {
            Column {
                Text(signInSteps(agent), style = MaterialTheme.typography.bodyMedium)
                if (agent == Agent.CODEX) {
                    TextButton(onClick = { Browser.open(context, CloudShell.CODEX_DEVICE_SIGN_IN) }) { Text("Open ChatGPT settings") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
