package com.pocketide.ui.screens.lock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CodeOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.github.gitHubAppChoice
import com.pocketide.model.LinkHealth
import com.pocketide.model.LockReason
import com.pocketide.ui.components.Tone
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.onboarding.GitHubAppFields
import com.pocketide.ui.screens.onboarding.GitHubAppForm
import com.pocketide.ui.screens.onboarding.GitHubAppHowTo
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.CenteredTitle
import com.pocketide.ui.shell.DeviceSignIn
import com.pocketide.ui.shell.DriveConnectPanel
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.GitHubConnectPanel
import com.pocketide.ui.shell.LimitedScreens
import com.pocketide.ui.shell.Links
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.SettingChoices
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StatusLine
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One screen per lock reason, each with its fix. */
@Composable
fun LockScreen(reason: LockReason) {
    LimitedScreens { nav ->
        when (reason) {
            LockReason.GitHubDisconnected -> GitHubDisconnected()
            LockReason.DriveDisconnected -> DriveDisconnected(nav)
            is LockReason.StorageFull -> StorageFull(reason.googleStorageFull, nav)
            is LockReason.OtherPhone -> OtherPhone(reason.deviceName)
            is LockReason.Unsupported -> Unsupported(reason.why)
        }
    }
}

/**
 * The app lock (fingerprint or screen lock). Android's prompt appears by itself when the screen
 * comes to the front, once per return; the button is there for a cancelled or failed prompt.
 * [prompting] is true while Android's prompt or its PIN screen is up.
 */
@Composable
fun AppLockScreen(
    onUnlock: () -> Unit,
    message: String? = null,
    deviceSecure: Boolean = true,
    onSetScreenLock: () -> Unit = {},
    prompting: Boolean = false,
) {
    // Asked on resume, not on first composition: a prompt requested while the activity is only
    // started can be dropped by the system. Once per return: the PIN screen is an activity of its
    // own that stops and resumes this one, so a stop during a prompt is not a return, and a
    // cancelled PIN screen does not bring the prompt straight back.
    var asked by remember { mutableStateOf(false) }
    val promptingNow by rememberUpdatedState(prompting)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!asked && deviceSecure) {
            asked = true
            onUnlock()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!promptingNow) asked = false }

    ShellPage(centered = true) {
        BrandMark(size = 72.dp)
        Gap(24.dp)
        CenteredTitle(Icons.Outlined.Lock, "PocketIDE is locked", "Unlock with your fingerprint or your screen lock.")
        Gap(24.dp)
        PrimaryAction("Unlock", onClick = onUnlock, enabled = deviceSecure)
        if (message != null) {
            Gap(12.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (!deviceSecure) {
            Gap(16.dp)
            NoticeCard(
                "This phone has no screen lock, so PocketIDE can't check it's you. Set a PIN, pattern or password to unlock.",
                Tone.WARN,
            )
            Gap(8.dp)
            SecondaryAction("Open screen lock settings", onClick = onSetScreenLock)
        }
    }
}

/** Re-checks access after a fix; the root swaps the lock away when it clears. */
private class Recheck(private val graph: AppGraph, private val launch: (suspend () -> Unit) -> Unit) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun run(before: suspend () -> Unit = {}) {
        busy = true
        error = null
        launch {
            try {
                before()
                graph.access.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "That didn't work. Check the internet and try again."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun rememberRecheck(graph: AppGraph): Recheck {
    val scope = rememberCoroutineScope()
    return remember(graph) { Recheck(graph) { block -> scope.launch { block() } } }
}

@Composable
private fun GitHubDisconnected() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val recheck = rememberRecheck(graph)
    val signIn = DeviceSignIn.of(graph)
    val openUrl: (String) -> Unit = { External.openUrl(context, it) }
    // Settings may not be reachable while locked, so an App that was deleted, or had device flow
    // switched off, is changed here.
    var changingApp by remember { mutableStateOf(false) }
    ShellPage {
        Gap(12.dp)
        CenteredTitle(
            Icons.Outlined.CodeOff,
            "GitHub disconnected",
            "PocketIDE's access to your GitHub was removed. Agents are paused so nothing gets lost.",
            tone = Tone.ERROR,
        )
        ConnectionStatus(graph)
        Gap(24.dp)
        if (changingApp) {
            ChangeGitHubApp(
                graph = graph,
                openUrl = openUrl,
                onSaved = {
                    changingApp = false
                    signIn.start()
                },
                onCancel = { changingApp = false },
            )
        } else {
            GitHubConnectPanel(
                signIn = signIn,
                openUrl = openUrl,
                onConnected = { recheck.run() },
                startLabel = "Reconnect GitHub",
            )
            QuietAction(
                "Change the GitHub App",
                onClick = {
                    signIn.reset()
                    changingApp = true
                },
            )
        }
        RecheckResult(recheck)
        WhatHappened(
            "Access ends when PocketIDE's sign-in is revoked on GitHub, or the account is gone. " +
                "Being offline never locks the app. Reconnecting uploads what waited.",
        )
        FinePrint("The app stays locked until both GitHub and Google Drive are connected.")
    }
}

/** The GitHub App's client ID and name, with how to make a new App; saving starts a new sign-in. */
@Composable
private fun ChangeGitHubApp(graph: AppGraph, openUrl: (String) -> Unit, onSaved: () -> Unit, onCancel: () -> Unit) {
    val choice = remember(graph) { gitHubAppChoice(graph) }
    val form = remember(choice) { choice.current().let { GitHubAppForm(it.clientId, it.slug) } }
    SectionLabel("Your GitHub App")
    GitHubAppHowTo(openUrl)
    Gap(16.dp)
    GitHubAppFields(form)
    Gap(16.dp)
    PrimaryAction("Save and reconnect", onClick = { if (form.save(choice) != null) onSaved() })
    QuietAction("Cancel", onClick = onCancel)
}

@Composable
private fun DriveDisconnected(nav: PocketNav) {
    val graph = rememberGraph()
    val recheck = rememberRecheck(graph)
    ShellPage {
        Gap(12.dp)
        CenteredTitle(
            Icons.Outlined.CloudOff,
            "Google Drive disconnected",
            "PocketIDE's access to your Drive was removed. Agents are paused so nothing gets lost.",
            tone = Tone.ERROR,
        )
        ConnectionStatus(graph)
        Gap(24.dp)
        DriveConnectPanel(auth = graph.driveAuth, onAuthorized = { recheck.run() }, onOpenHelp = nav::help, label = "Reconnect Google Drive")
        RecheckResult(recheck)
        WhatHappened(
            "Access ends when PocketIDE is removed from your Google account's third-party connections, " +
                "or disconnected in Drive → Manage apps. Being offline never locks the app.",
        )
        FinePrint("The app stays locked until both GitHub and Google Drive are connected.")
    }
}

private const val EMPTY_RECENTLY_DELETED = "Delete forever from Recently deleted"
private const val DELETED_KEEP_SPACE = "Deleted chats keep their Drive space for 30 days, until they are deleted forever."

@Composable
private fun StorageFull(googleStorageFull: Boolean, nav: PocketNav) {
    val graph = rememberGraph()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val recheck = rememberRecheck(graph)
    var raise by remember { mutableStateOf(false) }

    ShellPage {
        Gap(12.dp)
        if (googleStorageFull) {
            CenteredTitle(
                Icons.Outlined.SdCardAlert,
                "Google storage is full",
                "New chats are waiting safely on this phone. Sync has failed for a day, so new work is paused until there's space.",
                tone = Tone.WARN,
            )
        } else {
            CenteredTitle(
                Icons.Outlined.SdCardAlert,
                "PocketIDE's space is full",
                "PocketIDE's share of your Drive reached ${settings.driveLimitGb} GB. New chats are waiting safely on this phone.",
                tone = Tone.WARN,
            )
        }
        ConnectionStatus(graph)
        SectionLabel("Make space")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (googleStorageFull) {
                PrimaryAction("See what uses your Google storage", onClick = { nav.openExternal(Links.GOOGLE_STORAGE) })
                SecondaryAction("Delete PocketIDE's old or large chats", onClick = nav::yourData)
                SecondaryAction(EMPTY_RECENTLY_DELETED, onClick = nav::recentlyDeleted)
                SecondaryAction("Choose what to upload", onClick = nav::waitingUploads)
                FinePrint("$DELETED_KEEP_SPACE Gmail, Photos and Drive share one Google storage. Google One sells more if you want it.")
            } else {
                PrimaryAction("Raise the limit", onClick = { raise = true })
                SecondaryAction("Delete old or large chats", onClick = nav::yourData)
                SecondaryAction(EMPTY_RECENTLY_DELETED, onClick = nav::recentlyDeleted)
                SecondaryAction("Choose what to upload", onClick = nav::waitingUploads)
                FinePrint(DELETED_KEEP_SPACE)
                AutoTrimRow(
                    on = settings.autoTrimOldChats,
                    onChange = { on -> graph.settings.update { it.copy(autoTrimOldChats = on) } },
                )
            }
            QuietAction("Check again", onClick = { recheck.run { graph.sync.syncNow() } })
            RecheckResult(recheck)
        }
    }

    if (raise) {
        RaiseDriveLimitDialog(
            current = settings.driveLimitGb,
            onPick = { gb ->
                raise = false
                graph.settings.update { it.copy(driveLimitGb = gb) }
                recheck.run { graph.sync.syncNow() }
            },
            onDismiss = { raise = false },
        )
    }
}

@Composable
private fun AutoTrimRow(on: Boolean, onChange: (Boolean) -> Unit) {
    OutlinedCard {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Automatic rule", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Chats older than 12 months move to Recently deleted, after a 7-day notice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

@Composable
private fun RaiseDriveLimitDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val bigger = SettingChoices.driveLimitGb.filter { it.value > current }
    var picked by remember { mutableIntStateOf(bigger.firstOrNull()?.value ?: current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PocketIDE's Drive limit") },
        text = {
            Column(Modifier.selectableGroup()) {
                if (bigger.isEmpty()) {
                    Text("This is already the largest limit. Delete old chats or free Google storage instead.")
                }
                bigger.forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = picked == choice.value, role = Role.RadioButton, onClick = { picked = choice.value })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == choice.value, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(choice.label)
                    }
                }
                Gap(8.dp)
                Text(
                    "It still has to fit in your Google storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = bigger.isNotEmpty(), onClick = { onPick(picked) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OtherPhone(deviceName: String) {
    val graph = rememberGraph()
    val recheck = rememberRecheck(graph)
    var confirm by remember { mutableStateOf(false) }
    ShellPage {
        Gap(12.dp)
        CenteredTitle(
            Icons.Outlined.PhoneAndroid,
            "In use on $deviceName",
            "PocketIDE works on one phone at a time, so your chats never split in two.",
        )
        Gap(24.dp)
        OutlinedCard {
            Text(
                "If you use it here, $deviceName locks the next time it checks. Anything it did offline is kept as a " +
                    "conflict copy, never lost.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Gap(24.dp)
        PrimaryAction("Use here", onClick = { confirm = true }, busy = recheck.busy)
        RecheckResult(recheck)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Use PocketIDE on this phone?") },
            text = { Text("$deviceName will lock. You can move back any time the same way.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    recheck.run { graph.sync.takeOver() }
                }) { Text("Use here") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Unsupported(why: String) {
    ShellPage {
        Gap(12.dp)
        CenteredTitle(Icons.Outlined.PhonelinkErase, "PocketIDE can't run on this phone", why, tone = Tone.ERROR)
        SectionLabel("PocketIDE needs")
        OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Android 10 or newer, 64-bit (arm64)",
                    "Google Play services",
                    "4 GB of memory",
                    "8 GB of free storage",
                    "A screen lock (PIN, pattern or password)",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        FinePrint(
            "A phone below this can't hold the computer next to Android, so it is refused rather than left to crash. " +
                "Nothing was set up and nothing was sent anywhere.",
        )
    }
}

/** GitHub, Drive and what is waiting to upload, as the lock mockup shows them. */
@Composable
private fun ConnectionStatus(graph: AppGraph) {
    val access by graph.access.state.collectAsStateWithLifecycle()
    val waiting by graph.sync.waiting.collectAsStateWithLifecycle()
    SectionLabel("Status")
    OutlinedCard {
        LinkLine("GitHub", access.github, "Your code is safe there")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LinkLine("Google Drive", access.drive, "Your chats are safe there")
        if (waiting.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val count = waiting.size
            StatusLine(
                title = "Waiting to upload",
                detail = "$count ${if (count == 1) "chat" else "chats"} · ${Formats.bytes(waiting.sumOf { it.bytes })} · kept encrypted here",
                status = "Held",
                tone = Tone.WARN,
            )
        }
    }
}

@Composable
private fun LinkLine(name: String, health: LinkHealth, okDetail: String) {
    val (status, tone, detail) = when (health) {
        LinkHealth.OK -> Triple("Connected", Tone.OK, okDetail)
        LinkHealth.CHECKING -> Triple("Checking", Tone.NEUTRAL, null)
        LinkHealth.OFFLINE -> Triple("Offline", Tone.WARN, "Checked again when you're online")
        LinkHealth.REVOKED -> Triple("Disconnected", Tone.ERROR, "Access was removed")
        LinkHealth.NOT_CONNECTED -> Triple("Not connected", Tone.ERROR, null)
    }
    StatusLine(name, detail, status, tone)
}

@Composable
private fun RecheckResult(recheck: Recheck) {
    val error = recheck.error
    if (recheck.busy) {
        FinePrint("Checking…")
    } else if (error != null) {
        Gap(8.dp)
        NoticeCard(error, Tone.ERROR)
    }
}

@Composable
private fun WhatHappened(text: String) {
    var open by remember { mutableStateOf(false) }
    Gap(8.dp)
    QuietAction(if (open) "Hide" else "What happened?", onClick = { open = !open })
    AnimatedVisibility(open) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
