package com.pocketide.ui.screens.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.core.Redact
import com.pocketide.linux.ComputerSetup
import com.pocketide.linux.ComputerState
import com.pocketide.sync.RestoreChoice
import com.pocketide.sync.RestorePlan
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PhoneFactsReader
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.QuietAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.SettingChoices
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The plan's size of all set-up downloads (§2); the installer shows the exact total of its part once it starts. */
private const val SETUP_DOWNLOAD_TEXT = "about 1.5 GB in all"

private enum class Network { WIFI, MOBILE, OFFLINE }

/**
 * Step 3: the Ubuntu computer, Wi-Fi first, with this phone's own numbers. A returning owner
 * also sees the restore plan (§6.9) and picks how much mobile data it may use.
 */
@Composable
fun ComputerStepScreen(onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val state by graph.computer.state.collectAsStateWithLifecycle()
    val network by rememberNetwork(context)
    var startError by remember { mutableStateOf<String?>(null) }
    var askMobile by remember { mutableStateOf(false) }
    // Steps the installer has reported so far, so the owner sees progress, not one moving line.
    val stepsSeen = remember { mutableStateListOf<String>() }
    LaunchedEffect(state) {
        val step = (state as? ComputerState.Installing)?.step
        if (step != null && stepsSeen.lastOrNull() != step) stepsSeen += step
    }

    // The owner confirmed the set-up's size on mobile data: the data rules let exactly that
    // much through today, and the owner's own data settings stay as they are.
    fun install(onMobileData: Boolean) {
        startError = null
        if (onMobileData) ComputerSetup.setupDownloads().forEach { (kind, bytes) -> graph.dataBudget.allowOnce(kind, bytes) }
        graph.scope.launch {
            try {
                graph.computer.install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                startError = Redact.text(e.message ?: "Set-up stopped.").take(200)
            }
        }
    }
    val start = { if (network == Network.MOBILE) askMobile = true else install(onMobileData = false) }

    ShellPage {
        StepHeader(OnboardingStep.COMPUTER)
        Gap(24.dp)
        ScreenTitle(
            icon = Icons.Outlined.DeveloperBoard,
            title = "Set up the computer",
            subtitle = "A small Ubuntu computer inside PocketIDE runs the agents. Set-up downloads " +
                "$SETUP_DOWNLOAD_TEXT, and the computer takes about 2.5 GB on the phone.",
        )
        SectionLabel("This phone")
        PhoneNumbers(graph, network)

        SectionLabel("Set-up")
        when (val current = state) {
            ComputerState.NotInstalled -> NotInstalled(
                network = network,
                error = startError,
                onStart = start,
                onLater = onDone,
            )
            is ComputerState.Installing -> {
                Installing(current, stepsSeen)
                Gap(16.dp)
                PrimaryAction("Continue while it finishes", onClick = onDone)
            }
            is ComputerState.Updating -> {
                ProgressCard(current.what, null, null)
                Gap(16.dp)
                PrimaryAction("Continue", onClick = onDone)
            }
            ComputerState.Ready -> {
                SectionLabel("Check before you go on")
                CheckCard(listOf(CheckItem("Your computer is ready", "Ubuntu and the engine. Each agent is added the first time you open it.")))
                Gap(16.dp)
                PrimaryAction("Continue", onClick = onDone)
            }
            is ComputerState.Broken -> {
                NoticeCard(title = current.why, text = current.fix, tone = Tone.ERROR)
                startError?.let { Gap(8.dp); NoticeCard(it, Tone.ERROR) }
                Gap(16.dp)
                PrimaryAction("Try again", onClick = start)
                QuietAction("Later, on Wi-Fi", onClick = onDone)
            }
        }

        RestorePlanSection(graph, network)
    }

    if (askMobile) {
        AlertDialog(
            onDismissRequest = { askMobile = false },
            title = { Text("Set up on mobile data?") },
            text = {
                Text(
                    "Set-up downloads $SETUP_DOWNLOAD_TEXT: the computer now, each agent the first time you open it. " +
                        "On mobile data that can cost money or use up your plan; on Wi-Fi it costs nothing.\n\n" +
                        "For this set-up only, its downloads may use mobile data today, up to " +
                        "${Formats.bytes(ComputerSetup.setupDownloads().values.sum())}. Your data settings do not change.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askMobile = false
                    install(onMobileData = true)
                }) { Text("Use mobile data") }
            },
            dismissButton = { TextButton(onClick = { askMobile = false }) { Text("Wait for Wi-Fi") } },
        )
    }
}

@Composable
private fun PhoneNumbers(graph: AppGraph, network: Network) {
    val context = LocalContext.current
    val snapshot by graph.phone.snapshot.collectAsStateWithLifecycle()
    // The monitor may not be running yet during set-up; Android's own numbers fill the gap.
    val facts = remember { PhoneFactsReader.read(context, graph) }
    val totalRam = snapshot.totalRamBytes.takeIf { it > 0 } ?: facts.totalRamBytes
    val freeStorage = snapshot.storageFreeBytes.takeIf { snapshot.at > 0 } ?: facts.freeStorageBytes
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("Memory", if (snapshot.at > 0) Formats.part(totalRam - snapshot.availRamBytes, totalRam) + " in use" else Formats.bytes(totalRam))
            InfoRow("Free storage", Formats.bytes(freeStorage))
            InfoRow(
                "Connection",
                when (network) {
                    Network.WIFI -> "Wi-Fi"
                    Network.MOBILE -> "Mobile data"
                    Network.OFFLINE -> "Offline"
                },
            )
            if (snapshot.at > 0) InfoRow("Battery", "${snapshot.batteryPercent}%" + if (snapshot.charging) " · charging" else "")
        }
    }
}

@Composable
private fun NotInstalled(network: Network, error: String?, onStart: () -> Unit, onLater: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (network) {
            Network.WIFI -> NoticeCard("You're on Wi-Fi: a good time to set up.", Tone.OK)
            Network.MOBILE -> NoticeCard("You're on mobile data. Wi-Fi is better for a download this size.", Tone.WARN)
            Network.OFFLINE -> NoticeCard("No internet connection. Set-up starts when you're online.", Tone.WARN)
        }
        if (error != null) NoticeCard(error, Tone.ERROR)
        PrimaryAction(
            text = if (network == Network.MOBILE) "Set up on mobile data" else "Set up now",
            enabled = network != Network.OFFLINE,
            onClick = onStart,
        )
        QuietAction("Later, on Wi-Fi", onClick = onLater)
    }
}

@Composable
private fun Installing(state: ComputerState.Installing, stepsSeen: List<String>) {
    ProgressCard(state.step, state.fraction, state.bytesTotal.takeIf { it > 0 }?.let { Formats.part(state.bytesDone, it) })
    if (stepsSeen.size > 1) {
        Gap(12.dp)
        OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stepsSeen.dropLast(1).forEach { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Done", tint = toneColor(Tone.OK), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(done, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    FinePrint("You can leave this screen: set-up keeps going and continues after a break.")
}

@Composable
private fun ProgressCard(step: String, fraction: Float?, bytes: String?) {
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(step, style = MaterialTheme.typography.titleSmall)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (bytes != null) Text(bytes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shown only to a returning owner: Drive already holds chats from an earlier phone. */
@Composable
private fun RestorePlanSection(graph: AppGraph, network: Network) {
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    LaunchedEffect(Unit) {
        plan = try {
            graph.sync.restorePlan()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val current = plan ?: return
    if (current.driveTotalBytes <= 0) return

    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    var choiceName by rememberSaveable { mutableStateOf(RestoreChoice.WIFI_ONLY.name) }
    var started by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choice = RestoreChoice.valueOf(choiceName)
    val mobileAllowed = settings.mobileDailyLimitMb > 0

    SectionLabel("Your chats from before")
    OutlinedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow("In your Drive", Formats.bytes(current.driveTotalBytes))
            InfoRow("Downloads now", "${Formats.bytes(current.downloadNowBytes)} · ${current.sessionsNow} chats")
            InfoRow("Stays in Drive until opened", "${Formats.bytes(current.laterBytes)} · ${current.sessionsLater} chats")
            InfoRow("Free on this phone", Formats.bytes(current.freeBytes))
            InfoRow(
                "Connection",
                when (network) {
                    Network.WIFI -> "Wi-Fi"
                    Network.MOBILE -> "Mobile data"
                    Network.OFFLINE -> "Offline"
                },
            )
        }
    }
    Text(
        "Now: memory, settings, secrets and the last 30 days of chats. Older chats download when you open them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (current.downloadNowBytes > current.freeBytes) {
        Gap(8.dp)
        NoticeCard("This phone doesn't have room for the first download. Free some space first.", Tone.ERROR)
    }

    SectionLabel("Daily mobile data limit")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingChoices.dailyMobileLimitMb.forEach { option ->
            FilterChip(
                selected = settings.mobileDailyLimitMb == option.value,
                onClick = { graph.settings.update { it.copy(mobileDailyLimitMb = option.value) } },
                label = { Text(Formats.megabytes(option.value)) },
            )
        }
    }

    SectionLabel("Download over")
    Column(Modifier.selectableGroup()) {
        val wifiOnly = choice == RestoreChoice.WIFI_ONLY || !mobileAllowed
        RestoreOption("Wi-Fi only", "Waits for Wi-Fi if you're on mobile data.", wifiOnly, enabled = true) {
            choiceName = RestoreChoice.WIFI_ONLY.name
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        RestoreOption(
            title = "Mobile data up to ${Formats.megabytes(settings.mobileDailyLimitMb)} a day",
            detail = if (mobileAllowed) "The rest continues on Wi-Fi." else "Your daily limit is Off.",
            selected = choice == RestoreChoice.MOBILE_UP_TO_LIMIT && mobileAllowed,
            enabled = mobileAllowed,
        ) { choiceName = RestoreChoice.MOBILE_UP_TO_LIMIT.name }
    }
    Gap(12.dp)
    error?.let { NoticeCard(it, Tone.ERROR); Gap(8.dp) }
    if (started) {
        CheckCard(listOf(CheckItem("Restoring in the background", "Your chats appear as they arrive.")))
    } else {
        PrimaryAction(
            "Bring my chats back",
            enabled = current.downloadNowBytes <= current.freeBytes,
            onClick = {
                started = true
                error = null
                val picked = if (mobileAllowed) choice else RestoreChoice.WIFI_ONLY
                graph.scope.launch {
                    try {
                        graph.sync.restore(picked)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        started = false
                        error = Redact.text(e.message ?: "The restore could not start.").take(200)
                    }
                }
            },
        )
    }
}

@Composable
private fun RestoreOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The current connection, re-read every few seconds (Wi-Fi comes and goes during set-up). */
@Composable
private fun rememberNetwork(context: Context): androidx.compose.runtime.State<Network> {
    val state = remember { mutableStateOf(readNetwork(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            state.value = readNetwork(context)
            delay(3000)
        }
    }
    return state
}

private fun readNetwork(context: Context): Network {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Network.OFFLINE
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return Network.OFFLINE
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Network.OFFLINE
    return if (manager.isActiveNetworkMetered) Network.MOBILE else Network.WIFI
}
