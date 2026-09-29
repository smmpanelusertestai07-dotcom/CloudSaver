package com.pocketide.ui.screens.keys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Key
import com.pocketide.core.KeyStore
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ide.IdeState
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.KeepTypedInput
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ShellPage

/**
 * The owner's keys: environment variables every agent, command-line tool and terminal on the
 * computer sees (ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, GH_TOKEN, a project's own).
 * Sealed on this phone; values are never shown again after they are saved.
 */
@Composable
fun KeysScreen(onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val keys by graph.keys.keys.collectAsStateWithLifecycle()
    val ide by graph.ide.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Keys", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        Gap(8.dp)
        Text(
            "API keys and tokens for the agents and your apps. Each one is an environment variable that the agents, " +
                "their command-line tools and the terminal all see. They are sealed on this phone with a key in its secure " +
                "hardware, and never leave it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onHelpPage(DocsContent.KEYS_ID) }) { Text("Which keys, and when you need them") }
        Gap(8.dp)
        if (changed && ide is IdeState.On) {
            NoticeCard("Restart code-server (agent screen > menu > Restart code-server) to give the agents the new keys.")
            Gap(12.dp)
        }
        if (keys.isNotEmpty()) {
            OutlinedCard {
                keys.forEachIndexed { index, key ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    KeyRow(key, onDelete = {
                        graph.keys.remove(key.name)
                        changed = true
                    })
                }
            }
            Gap(16.dp)
        }
        PrimaryAction("Add a key", onClick = { adding = true })
    }
    if (adding) {
        AddKeyDialog(
            onSave = { name, value ->
                graph.keys.put(name, value)
                changed = true
                adding = false
            },
            onCancel = { adding = false },
        )
    }
}

@Composable
private fun KeyRow(key: Key, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Text("•".repeat(MASK), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${key.name}") }
    }
}

@Composable
private fun AddKeyDialog(onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = KeepTypedInput,
        title = { Text("Add a key") },
        text = {
            DialogBody {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.trim().uppercase()
                        error = null
                    },
                    label = { Text("Name, like OPENAI_API_KEY") },
                    singleLine = true,
                    isError = error != null,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text("Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = KeyStore.problem(name, value.trim())
                if (error == null) onSave(name, value.trim())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private const val MASK = 12
