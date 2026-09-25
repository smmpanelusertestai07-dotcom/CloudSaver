package com.pocketide.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.linux.ComputerState
import com.pocketide.ui.components.InfoRow
import com.pocketide.ui.shell.CheckCard
import com.pocketide.ui.shell.CheckItem
import com.pocketide.ui.shell.Formats
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.OnboardingStep
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.PhoneFactsReader
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.ScreenTitle
import com.pocketide.ui.shell.SectionLabel
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.shell.StepHeader
import com.pocketide.ui.shell.rememberGraph

/**
 * Step 3: the Ubuntu computer, Wi-Fi first, with this phone's own numbers. A returning owner
 * first sees the restore plan (§6.9) and picks how much mobile data it may use.
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

        // A returning owner's chats come first: the buttons below leave the step. Home offers
        // the plan again after set-up until a restore starts.
        val offer = rememberRestoreOffer()
        val pending by offer.pending.collectAsStateWithLifecycle()
        val returning = rememberSaveable { offer.pending.value }
        if (returning || pending) RestorePlanPanel()

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
