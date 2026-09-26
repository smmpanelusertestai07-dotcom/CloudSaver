package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.core.Redact
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.DriveConnectPanel
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StatusLine
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph
import com.pocketide.vault.GitHubAppMissingException
import com.pocketide.vault.KeyState
import com.pocketide.vault.VaultKeys
import com.pocketide.vault.WrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Step 2: Google Drive (`drive.appdata`) and the chats' key. A returning owner gets the key
 * rebuilt from the Drive and GitHub halves; a first phone gets a new key. Nothing to write down.
 */
@Composable
fun DriveStepScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val email by graph.driveAuth.email.collectAsStateWithLifecycle()
    var justAuthorized by remember { mutableStateOf(false) }
    var authorizedEmail by remember { mutableStateOf<String?>(null) }
    // The owner may add a screen lock in Settings and come back: check again on every return.
    var secure by remember { mutableStateOf(graph.appLock.deviceSecure()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { secure = graph.appLock.deviceSecure() }
    val connected = email != null || justAuthorized
    var keyReady by remember { mutableStateOf(false) }
    val restoreOffer = rememberRestoreOffer()

    ShellPage {
        StepHeader(OnboardingStep.DRIVE)
        Gap(24.dp)
        ScreenTitle(
            icon = Icons.Outlined.CloudQueue,
            title = "Connect Google Drive",
            subtitle = "For your AI chats, memory and settings, so they survive a lost phone or an uninstall.",
        )
        SectionLabel("Kept in a hidden app folder")
        CheckCard(
            listOf(
                CheckItem("Not shown in your Drive", "And it can't be shared or made public"),
                CheckItem("Only PocketIDE can open it", "Other apps and people see nothing"),
                CheckItem("Locked with your own key", "Encrypted on this phone first: not even Google can read it"),
            ),
        )

        if (!connected) {
            Gap(16.dp)
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Google will ask you to allow:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "“See, create, and delete its own configuration data in your Google Drive”",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Nothing else. PocketIDE can't open your photos, documents or any other file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Gap(16.dp)
            if (!secure) {
                NoticeCard(
                    title = "Set a screen lock first",
                    text = "The key to your chats is protected by this phone's screen lock. Add a PIN, pattern or password, then come back here.",
                    tone = Tone.WARN,
                )
                Gap(12.dp)
                SecondaryAction("Open screen lock settings", onClick = { External.openSecuritySettings(context) })
                Gap(12.dp)
            }
            DriveConnectPanel(
                auth = graph.driveAuth,
                enabled = secure,
                onAuthorized = {
                    authorizedEmail = it
                    justAuthorized = true
                },
            )
            FinePrint("Disconnect later and the app locks until you reconnect. Nothing is lost.")
        } else {
            SectionLabel("Check before you go on")
            OutlinedCard {
                StatusLine("Google Drive", email ?: authorizedEmail, "Connected", Tone.OK)
            }
            SectionLabel("Your chats' key")
            KeySetup(
                vault = graph.vault,
                openInstallPage = { External.openUrl(context, graph.gitHubAuth.installUrl()) },
                // Opened with the extra password: this phone's settings must know it is on.
                onPasswordUsed = { graph.settings.update { it.copy(extraPassword = true) } },
                onReady = { keyReady = true },
                // A rebuilt key means chats may wait in Drive: the restore is offered until it starts.
                onKeyMade = restoreOffer::keyReady,
            )
            if (keyReady) {
                Gap(24.dp)
                PrimaryAction("Continue", onClick = onDone)
            }
        }
    }
}

private sealed interface KeyPhase {
    data object Working : KeyPhase
    data class NeedsPassword(val wrong: Boolean) : KeyPhase
    data class Lost(val why: String) : KeyPhase

    /** [appMissing]: the keyring could not be made until PocketIDE's GitHub App is installed. */
    data class Failed(val why: String, val appMissing: Boolean = false) : KeyPhase
    data class Ready(val restored: Boolean) : KeyPhase
}

/**
 * Rebuilds the key from both halves when they exist, or makes a new one on a first phone. A key
 * that exists but cannot be rebuilt is never silently replaced: the owner decides.
 */
@Composable
private fun KeySetup(
    vault: VaultKeys,
    openInstallPage: () -> Unit,
    onPasswordUsed: () -> Unit,
    onReady: () -> Unit,
    onKeyMade: (restored: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<KeyPhase>(KeyPhase.Working) }
    var confirmNewKey by remember { mutableStateOf(false) }

    fun attempt(block: suspend () -> KeyPhase) {
        phase = KeyPhase.Working
        scope.launch {
            phase = keyPhaseOf(block)
            (phase as? KeyPhase.Ready)?.let { ready ->
                onKeyMade(ready.restored)
                onReady()
            }
        }
    }

    fun restore(password: CharArray?) = attempt { restoreKey(vault, password, onPasswordUsed) }

    LaunchedEffect(Unit) {
        when (vault.state.value) {
            KeyState.Ready, KeyState.OnlyOnPhone -> {
                phase = KeyPhase.Ready(restored = true)
                onReady()
            }
            else -> restore(null)
        }
    }

    KeyPhaseView(
        phase = phase,
        openInstallPage = openInstallPage,
        onRetry = { restore(null) },
        onPassword = { restore(it) },
        onKeyCopy = { text ->
            attempt {
                vault.importKeyCopy(text)
                KeyPhase.Ready(restored = true)
            }
        },
        onNewKey = { confirmNewKey = true },
    )

    if (confirmNewKey) {
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text("Start with a new key?") },
            text = { Text("Chats saved with the old key can't be opened with a new one. Use this only if the old key is gone for good.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewKey = false
                    attempt {
                        vault.setUp()
                        KeyPhase.Ready(restored = false)
                    }
                }) { Text("Make a new key") }
            },
            dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("Cancel") } },
        )
    }
}

/** What [block] ended in; a failure becomes the phase that tells the owner what to do. */
private suspend fun keyPhaseOf(block: suspend () -> KeyPhase): KeyPhase = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: IOException) {
    KeyPhase.Failed("No connection. Check the internet and try again.")
} catch (e: GitHubAppMissingException) {
    KeyPhase.Failed(e.message.orEmpty(), appMissing = true)
} catch (e: Exception) {
    KeyPhase.Failed(PlainError.of(e))
}

/** Rebuilds the key from its halves, or makes the first one. [password] is wiped afterwards. */
private suspend fun restoreKey(vault: VaultKeys, password: CharArray?, onPasswordUsed: () -> Unit): KeyPhase = try {
    when (val result = vault.restore(password)) {
        KeyState.Ready, KeyState.OnlyOnPhone -> {
            if (password != null) onPasswordUsed()
            KeyPhase.Ready(restored = true)
        }
        KeyState.NeedsPassword -> KeyPhase.NeedsPassword(wrong = password != null)
        KeyState.None -> {
            vault.setUp()
            KeyPhase.Ready(restored = false)
        }
        is KeyState.Lost -> KeyPhase.Lost(Redact.text(result.why))
    }
} catch (_: WrongPasswordException) {
    KeyPhase.NeedsPassword(wrong = true)
} finally {
    password?.fill('\u0000')
}

@Composable
private fun KeyPhaseView(
    phase: KeyPhase,
    openInstallPage: () -> Unit,
    onRetry: () -> Unit,
    onPassword: (CharArray) -> Unit,
    onKeyCopy: (String) -> Unit,
    onNewKey: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (phase) {
            KeyPhase.Working -> PrimaryAction("Setting up encryption…", onClick = {}, busy = true)
            is KeyPhase.Ready -> CheckCard(
                listOf(
                    CheckItem(
                        if (phase.restored) "Your key is back" else "Encryption is set up",
                        "Nothing to write down. A new phone rebuilds the key from your Drive and GitHub.",
                        icon = Icons.Outlined.Key,
                    ),
                ),
            )
            is KeyPhase.NeedsPassword -> ExtraPasswordEntry(wrong = phase.wrong, onSubmit = onPassword)
            is KeyPhase.Failed -> {
                NoticeCard(phase.why, Tone.ERROR)
                if (phase.appMissing) {
                    PrimaryAction("Install PocketIDE on your GitHub", onClick = openInstallPage)
                    SecondaryAction("Try again", onClick = onRetry)
                } else {
                    PrimaryAction("Try again", onClick = onRetry)
                }
            }
            is KeyPhase.Lost -> {
                NoticeCard(
                    title = "Your old key can't be rebuilt",
                    text = "${phase.why} Your code on GitHub is not affected.",
                    tone = Tone.ERROR,
                )
                KeyCopyEntry(onSubmit = onKeyCopy)
                QuietAction("Try again", onClick = onRetry)
                QuietAction("Start with a new key", onClick = onNewKey)
            }
        }
    }
}

@Composable
private fun ExtraPasswordEntry(wrong: Boolean, onSubmit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    NoticeCard(
        title = "Your extra password",
        text = "You set an extra password on your key. Type it once to open your chats on this phone.",
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Extra password") },
        singleLine = true,
        isError = wrong,
        supportingText = if (wrong) ({ Text("That password did not open the key. Try again.") }) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryAction(
        "Open my chats",
        enabled = password.isNotEmpty(),
        onClick = {
            val chars = password.toCharArray()
            password = ""
            onSubmit(chars)
        },
    )
}

@Composable
private fun KeyCopyEntry(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Text(
        "If you saved a key copy (Settings → Advanced), paste it here to open your old chats.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Saved key copy") },
        // A key copy is several lines (a comment and a key per generation), so the field keeps them.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    SecondaryAction(
        "Use this key copy",
        enabled = text.isNotBlank(),
        onClick = {
            val value = text.trim()
            text = ""
            onSubmit(value)
        },
    )
}
