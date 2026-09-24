package com.pocketide.ui.screens.settings

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.BuildConfig
import com.pocketide.core.Device
import com.pocketide.core.Ist
import com.pocketide.core.Redact
import com.pocketide.core.Settings
import com.pocketide.linux.ComputerInfo
import com.pocketide.linux.ComputerState
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.Diagnostics
import com.pocketide.ui.shell.ExtraPasswordRules
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.update.UpdateState
import com.pocketide.vault.KeyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AdvancedDialog { RESET, SET_PASSWORD, REMOVE_PASSWORD, KEY_WARNING, DIAGNOSTICS }

/** Rare tools: rebuild the computer, the extra password, a key copy, and diagnostics. */
@Composable
internal fun AdvancedSection(graph: AppGraph, settings: Settings) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val computer by graph.computer.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<AdvancedDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    // Shown once, then forgotten: it is never saved or put in the saved-instance state.
    var keyCopy by remember { mutableStateOf<String?>(null) }

    fun run(done: String?, block: suspend () -> Unit) {
        busy = true
        notice = null
        scope.launch {
            try {
                block()
                if (done != null) notice = done to Tone.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = Redact.text(e.message ?: "That didn't work. Try again.").take(200) to Tone.ERROR
            } finally {
                busy = false
            }
        }
    }

    SectionLabel("Advanced")
    SettingsGroup(
        listOf(
            {
                ActionRow(
                    "Reset computer",
                    when (computer) {
                        is ComputerState.Installing, is ComputerState.Updating -> "Being set up now"
                        else -> "Rebuild Ubuntu from scratch. Projects and chats are not touched."
                    },
                    onClick = { dialog = AdvancedDialog.RESET },
                    icon = Icons.Outlined.RestartAlt,
                )
            },
            {
                if (settings.extraPassword) {
                    ActionRow("Remove the extra password", "Your key's GitHub half is wrapped with it now", onClick = {
                        dialog = AdvancedDialog.REMOVE_PASSWORD
                    }, icon = Icons.Outlined.Password)
                } else {
                    ActionRow("Extra password", "Off. Protects your chats even if both accounts are taken", onClick = {
                        dialog = AdvancedDialog.SET_PASSWORD
                    }, icon = Icons.Outlined.Password)
                }
            },
            {
                ActionRow(
                    "Save a key copy",
                    "Only for losing this phone and GitHub together",
                    onClick = { dialog = AdvancedDialog.KEY_WARNING },
                    icon = Icons.Outlined.Key,
                )
            },
            {
                ActionRow("Diagnostics", "Versions and recent errors, with secrets removed", onClick = {
                    dialog = AdvancedDialog.DIAGNOSTICS
                }, icon = Icons.Outlined.BugReport)
            },
        ),
    )
    if (busy) {
        FinePrint("Working…")
    } else {
        notice?.let { (text, tone) ->
            Gap(8.dp)
            NoticeCard(text, tone)
        }
    }

    when (dialog) {
        AdvancedDialog.RESET -> ConfirmDialog(
            title = "Reset the computer?",
            text = "Ubuntu, the engine and the agents are downloaded again (about 1.5 GB, on Wi-Fi by default). " +
                "Your projects are on GitHub and your chats in Drive, so nothing of yours is lost. Agents' sign-ins must be done again.",
            confirm = "Reset",
            onConfirm = {
                dialog = null
                // The rebuild outlives this screen, so it runs in the app's scope.
                graph.scope.launch {
                    try {
                        graph.computer.reset()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notice = Redact.text(e.message ?: "The reset could not start.").take(200) to Tone.ERROR
                    }
                }
                notice = "Resetting. You can follow it on the Computer screen." to Tone.OK
            },
            onDismiss = { dialog = null },
        )
        AdvancedDialog.SET_PASSWORD -> ExtraPasswordDialog(
            onSave = { password ->
                dialog = null
                run("Extra password set. You'll type it only when setting up a new phone.") {
                    try {
                        graph.vault.setExtraPassword(password)
                        graph.settings.update { it.copy(extraPassword = true) }
                    } finally {
                        password.fill('\u0000')
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        AdvancedDialog.REMOVE_PASSWORD -> ConfirmDialog(
            title = "Remove the extra password?",
            text = "Your chats can then be opened by someone who holds both your Google and your GitHub accounts, as without it.",
            confirm = "Remove",
            onConfirm = {
                dialog = null
                run("Extra password removed.") {
                    graph.vault.setExtraPassword(null)
                    graph.settings.update { it.copy(extraPassword = false) }
                }
            },
            onDismiss = { dialog = null },
        )
        AdvancedDialog.KEY_WARNING -> ConfirmDialog(
            title = "Save a key copy?",
            text = "Your chats' key is on this phone and in two halves in Drive and GitHub, so you normally need nothing. " +
                "A copy covers one case: losing this phone and your GitHub account together.\n\n" +
                "Anyone with the copy and your Drive can read your chats. Keep it where only you can reach it, " +
                "such as a password manager. It is shown once.",
            confirm = "Show it",
            onConfirm = {
                dialog = null
                if (activity == null) {
                    notice = "The key copy can't be shown from here." to Tone.ERROR
                } else {
                    graph.appLock.authenticate(activity, "Show your key copy") { ok ->
                        if (ok) run(null) { keyCopy = graph.vault.exportKeyCopy() }
                    }
                }
            },
            onDismiss = { dialog = null },
        )
        AdvancedDialog.DIAGNOSTICS -> DiagnosticsDialog(graph, onDismiss = { dialog = null })
        null -> Unit
    }

    keyCopy?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your key copy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NoticeCard("Shown once. Long-press to copy it into a password manager. Never paste it into a chat.", Tone.WARN)
                    SelectableText(monospace(key), sizeSp = 14f, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { keyCopy = null }) { Text("I saved it") } },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExtraPasswordDialog(onSave: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val problem = ExtraPasswordRules.problem(password.toCharArray(), confirm.toCharArray())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extra password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "It wraps the GitHub half of your key. Then even someone with both your Google and GitHub accounts " +
                        "can't read your chats. Agents are not affected, and you type it only when setting up a new phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                NoticeCard("If you forget it and lose this phone, your chats are lost for good. Nobody can reset it.", Tone.WARN)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (at least ${ExtraPasswordRules.MIN_LENGTH} characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Type it again") },
                    singleLine = true,
                    isError = tried && problem != null,
                    supportingText = if (tried && problem != null) ({ Text(problem) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (problem == null) {
                    val chars = password.toCharArray()
                    password = ""
                    confirm = ""
                    onSave(chars)
                }
            }) { Text("Set password") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiagnosticsDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { report = buildReport(context, graph) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Secrets are removed. Long-press to select, then Share if someone is helping you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(12.dp)
                SelectableText(monospace(report ?: "Collecting…"), sizeSp = 13f, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private suspend fun buildReport(context: Context, graph: AppGraph): String {
    val access = graph.access.state.value
    val info: ComputerInfo? = try {
        graph.computer.info()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val facts = buildList {
        add("PocketIDE" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Phone" to Device.name())
        add("Computer" to computerLabel(graph.computer.state.value))
        info?.let {
            add("Kernel" to it.kernel.ifBlank { "unknown" })
            add("Ubuntu" to (it.ubuntu ?: "not installed"))
            add("Engine" to (it.codeServer ?: "not installed"))
            add("Antigravity hub" to (it.agy ?: "not installed"))
        }
        add("GitHub" to access.github.name.lowercase())
        add("Google Drive" to access.drive.name.lowercase())
        add("Key" to keyLabel(graph.vault.state.value))
        add("Sync" to syncLabel(graph.sync.status.value))
        add("Limiter" to graph.limiter.guard.value.name.lowercase())
    }
    val errors = buildList {
        (graph.sync.status.value as? SyncStatus.Error)?.let { add("Sync: ${it.why}") }
        (graph.computer.state.value as? ComputerState.Broken)?.let { add("Computer: ${it.why}") }
        (graph.updater.state.value as? UpdateState.Failed)?.let { add("Update: ${it.why}") }
        addAll(recentExits(context))
    }
    return Diagnostics.report(facts, errors)
}

private fun computerLabel(state: ComputerState): String = when (state) {
    ComputerState.NotInstalled -> "not installed"
    is ComputerState.Installing -> "setting up: ${state.step}"
    ComputerState.Ready -> "ready"
    is ComputerState.Updating -> "updating ${state.what}"
    is ComputerState.Broken -> "needs a fix"
}

private fun keyLabel(state: KeyState): String = when (state) {
    KeyState.None -> "not set up"
    KeyState.Ready -> "ready"
    KeyState.OnlyOnPhone -> "only on this phone"
    KeyState.NeedsPassword -> "needs the extra password"
    is KeyState.Lost -> "lost"
}

private fun syncLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "idle"
    is SyncStatus.Running -> "running"
    is SyncStatus.UpToDate -> "up to date at ${Ist.dateTime(status.at)}"
    is SyncStatus.Waiting -> "waiting since ${Ist.dateTime(status.since)}"
    is SyncStatus.Error -> "error"
}

/** Android's own record of how the app last ended (crashes, not-responding, low memory), Android 11+. */
private fun recentExits(context: Context): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
    val exits = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 10) }.getOrDefault(emptyList())
    return exits
        .filter { it.reason in NOTABLE_EXITS }
        .take(5)
        .map { exit -> "${Ist.dateTime(exit.timestamp)}: ${exitName(exit.reason)}${exit.description?.let { " · $it" }.orEmpty()}" }
}

private val NOTABLE_EXITS = setOf(
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
)

private fun exitName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_CRASH -> "app crashed"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
    ApplicationExitInfo.REASON_ANR -> "not responding"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by Android for memory"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed for heavy resource use"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
    else -> "ended"
}

private fun monospace(text: String): CharSequence = SpannableString(text).apply {
    setSpan(TypefaceSpan(Typeface.MONOSPACE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}
