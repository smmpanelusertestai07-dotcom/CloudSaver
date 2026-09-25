package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.pocketide.linux.ComputerState
import com.pocketide.sync.RestoreChoice
import com.pocketide.sync.RestorePlan
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.components.Tone
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PhoneFactsReader
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.SettingChoices
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
        SetUpComputer(state, onLater = onDone)
        when (val current = state) {
            is ComputerState.Installing -> {
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
            ComputerState.NotInstalled, is ComputerState.Broken -> Unit
        }

        RestorePlanSection(graph, network)
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
